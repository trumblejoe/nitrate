package dev.nitrate.core.account

import dev.nitrate.core.companion.CompanionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompanionStatusTest {
    private fun value(g: ServerSettingsGroup, label: String) = g.settings.first { it.label == label }

    @Test fun installed() {
        val g = CompanionStatus.group(Result.success(CompanionInfo("0.1.38", setOf("chat", "lines", "diary", "maps"))), 16 to 1200L)
        assertEquals("Installed and answering", value(g, "Plugin").value)
        assertEquals("0.1.38", value(g, "Version").value)
        assertEquals("On", value(g, "Watch diary").value)
        assertEquals("Off", value(g, "Single sign-on").value)
        assertTrue(value(g, "Single sign-on").note!!.contains("not set up"))
        assertEquals("16 titles, 1200 lines indexed.", value(g, "Search what people say").note)
        assertEquals("maps", value(g, "Newer features").value)
    }

    @Test fun nothingIndexedYet() {
        val g = CompanionStatus.group(Result.success(CompanionInfo("0.1.38", setOf("lines"))), 0 to 0L)
        assertTrue(value(g, "Search what people say").note!!.contains("Nothing indexed yet"))
    }

    @Test fun missingOrBroken() {
        assertEquals("Not installed", value(CompanionStatus.group(Result.success(null)), "Plugin").value)
        val broken = value(CompanionStatus.group(Result.failure(IllegalStateException("500 Internal Server Error"))), "Plugin")
        assertEquals("Not answering", broken.value)
        assertTrue(broken.note!!.contains("500"))
    }
}

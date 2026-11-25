package dev.nitrate.core.stats

import dev.nitrate.core.diary.DiaryEntry
import dev.nitrate.core.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Watching with groups is kept apart from the user's own figures, and each group gets its share. */
class SplitStatsTest {
    private val jan2026 = 1_767_225_600_000L + 86_400_000L * 10

    private fun entry(id: String, groups: List<String> = emptyList()) = DiaryEntry(
        "d-$id", id, ItemKind.EPISODE, "Ep $id", "show", "The Night Reel", 1, 1, 2025, 45 * 60_000L, listOf("Drama"),
        jan2026, null, null, false, groups,
    )

    @Test fun groupWatchesNeverCountAsTheUsersOwn() {
        val diary = listOf(entry("a"), entry("b", listOf("ts")), entry("c", listOf("ts")), entry("d", listOf("k")))
        val s = SplitStatsBuilder.build(diary, emptyList(), 2026, 0) { if (it == "ts") "Tommy & Suzy" else null }
        assertEquals(1, s.own.episodes)
        assertEquals(3, s.withGroups.episodes)
        assertEquals(listOf("Tommy & Suzy" to 2, "A past group" to 1), s.groups.map { it.name to it.count })
        assertEquals(90L, s.groups.first().minutes)
        assertEquals("With Tommy & Suzy", dev.nitrate.core.diary.Diary.withLabel(diary[1]) { if (it == "ts") "Tommy & Suzy" else null })
        assertEquals(null, dev.nitrate.core.diary.Diary.withLabel(diary[0]) { null })
    }
}

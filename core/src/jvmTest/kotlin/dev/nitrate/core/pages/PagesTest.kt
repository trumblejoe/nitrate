package dev.nitrate.core.pages

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PagesTest {
    private fun row(key: String, n: Int = 1) = MediaRow(key, key.substringAfterLast('/'), List(n) { MediaItem("$key-$it", "x", ItemKind.MOVIE) }, RowDisplay())

    private val builtIn = listOf("home/continue_watching", "home/next_up", "home/latest/a")

    @Test
    fun `untouched page keeps the app's order`() {
        assertEquals(builtIn, PageComposer.entries(builtIn, PageLayout()).map { it.rowKey })
    }

    @Test
    fun `a new built-in row lands after the row it follows by default`() {
        val layout = PageLayout(listOf(RowEntry.builtIn("home/latest/a"), RowEntry.builtIn("home/continue_watching")))
        // next_up is new: it follows continue_watching.
        assertEquals(listOf("home/latest/a", "home/continue_watching", "home/next_up"), PageComposer.entries(builtIn, layout).map { it.rowKey })
        // A row new at the very top goes first.
        assertEquals("home/new", PageComposer.entries(listOf("home/new") + builtIn, layout).first().rowKey)
    }

    @Test
    fun `rows apply hiding, renaming, custom rows and skip empty ones`() {
        val custom = RowEntry("c1", RowSource.Query(ItemQuery(genres = listOf("Horror"))), title = "Scary")
        val entries = listOf(
            RowEntry.builtIn("home/next_up").copy(hidden = true),
            custom,
            RowEntry.builtIn("home/latest/a").copy(title = "Fresh"),
            RowEntry.builtIn("home/continue_watching"),
        )
        val rows = PageComposer.rows(entries, listOf(row("home/next_up"), row("home/latest/a"), row("home/continue_watching", 0)), mapOf("c1" to row("custom/c1")))
        assertEquals(listOf("custom/c1", "home/latest/a"), rows.map { it.key })
        assertEquals("Fresh", rows[1].title)
    }

    @Test
    fun `moving clamps at the ends`() {
        val entries = builtIn.map(RowEntry::builtIn)
        assertEquals(listOf("home/next_up", "home/continue_watching", "home/latest/a"), PageComposer.move(entries, "builtin:home/next_up", -1).map { it.rowKey })
        assertEquals(builtIn, PageComposer.move(entries, "builtin:home/continue_watching", -5).map { it.rowKey })
    }

    @Test
    fun `layouts survive a round trip through JSON`() {
        val layout = PageLayout(listOf(RowEntry("c1", RowSource.Collection("x", "Bond")), RowEntry.builtIn("home/next_up").copy(hidden = true)))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(layout, json.decodeFromString<PageLayout>(json.encodeToString(layout)))
    }

    @Test
    fun `query description`() {
        val q = ItemQuery(types = listOf(ItemKind.MOVIE), genres = listOf("Horror"), yearFrom = 1980, yearTo = 1989, watched = WatchFilter.UNWATCHED, sort = QuerySort.RANDOM)
        assertEquals("Movies · Unwatched · Horror · 1980–1989 · Random", q.describe())
    }

    @Test
    fun `quality badges`() {
        val atmos = QualityBadges.AudioInfo("eac3", null, "English - Dolby Digital+ 5.1 Atmos", 6)
        assertEquals(listOf("4K", "Dolby Vision", "Atmos", "5.1"), QualityBadges.of(3840, 2160, "DOVIWithHDR10", listOf(atmos)))
        assertEquals(listOf("HD", "HDR10+"), QualityBadges.of(1920, 1080, "HDR10Plus", emptyList()))
        assertEquals(listOf("TrueHD", "7.1"), QualityBadges.of(720, 480, "SDR", listOf(QualityBadges.AudioInfo("truehd", null, null, 8))))
        assertEquals(emptyList<String>(), QualityBadges.of(null, null, null, emptyList()))
    }
}

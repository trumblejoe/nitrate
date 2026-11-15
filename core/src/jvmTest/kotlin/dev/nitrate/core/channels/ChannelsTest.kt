package dev.nitrate.core.channels

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ChannelsTest {
    private fun p(start: Long, end: Long, title: String) = ChannelProgram("i-$title", start, end, title, null, "Episode", null, true)

    @Test fun timeSlotsReadPlainly() {
        assertEquals("Weekends 8:00–10:30", Channels.describe(ChannelBlock("Cartoons", listOf(6, 0), 480, 630, emptyList())))
        assertEquals("Weekdays 18:00–20:00", Channels.describe(ChannelBlock("News", listOf(1, 2, 3, 4, 5), 1080, 1200, emptyList())))
        assertEquals("Every day 23:00–1:00", Channels.describe(ChannelBlock("Late", (0..6).toList(), 1380, 60, emptyList())))
        assertEquals("Mon, Wed 7:05–8:00", Channels.describe(ChannelBlock("x", listOf(3, 1), 425, 480, emptyList())))
    }

    @Test fun whatsOnIsTheCurrentProgramOrTheNext() {
        val programs = listOf(p(0, 100, "A"), p(120, 200, "B"))
        assertEquals("A", Channels.onNow(programs, 50)?.title)
        assertEquals("B", Channels.onNow(programs, 110)?.title)
        assertNull(Channels.onNow(programs, 300))
    }

    @Test fun lineupsAddOnceAndReorder() {
        val show = MediaItem("s1", "Show", ItemKind.SERIES)
        val movie = MediaItem("m1", "Movie", ItemKind.MOVIE)
        val lineup = Channels.add(Channels.add(Channels.add(emptyList(), show), movie), show)
        assertEquals(listOf(SourceKind.SERIES, SourceKind.ITEM), lineup.map { it.kind })
        assertEquals(listOf("m1", "s1"), Channels.move(lineup, 1, -1).map { it.id })
        assertEquals(listOf("s1", "m1"), Channels.move(lineup, 1, 5).map { it.id })
    }

    @Test fun problemsAreCaughtBeforeSaving() {
        val src = ChannelSource(SourceKind.SERIES, "s", "S")
        assertEquals("Give the channel a name.", Channels.problem(HouseChannel(sources = listOf(src))))
        assertEquals("Add something for the channel to play.", Channels.problem(HouseChannel(name = "A")))
        assertEquals("Choose who can watch it, or let everyone.", Channels.problem(HouseChannel(name = "A", sources = listOf(src), everyone = false)))
        assertNull(Channels.problem(HouseChannel(name = "A", sources = listOf(src))))
        assertEquals(4, Channels.nextNumber(listOf(HouseChannel(number = 3), HouseChannel(number = 1))))
    }
}

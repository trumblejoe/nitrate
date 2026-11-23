package dev.nitrate.core.music

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenQueueTest {
    private fun songs(n: Int) = (1..n).map { MediaItem("s$it", "Song $it", ItemKind.AUDIO) }
    private fun ids(q: ListenQueue) = q.playing().map { it.id }

    @Test fun `plays in order from the start chosen`() {
        val q = ListenQueue()
        q.set(songs(4), start = 2, shuffle = false)
        assertEquals(listOf("s1", "s2", "s3", "s4"), ids(q))
        assertEquals("s3", q.current?.id)
        assertEquals(3, q.nextPosition(Repeat.OFF))
        q.moveTo(3)
        assertNull(q.nextPosition(Repeat.OFF))
        assertEquals(0, q.nextPosition(Repeat.ALL))
        assertEquals(3, q.nextPosition(Repeat.ONE))
        assertEquals(0, q.nextPosition(Repeat.ONE, manual = true))
    }

    @Test fun `shuffled, the chosen song comes first and every song once`() {
        val q = ListenQueue(Random(7))
        q.set(songs(6), start = 4, shuffle = true)
        assertEquals("s5", q.playing().first().id)
        assertEquals(0, q.position)
        assertEquals(songs(6).map { it.id }.toSet(), ids(q).toSet())
        q.moveTo(3)
        val on = q.current
        q.setShuffle(false)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5", "s6"), ids(q))
        assertEquals(on, q.current)
    }

    @Test fun `adding next, at the end, removing and moving keep the song on`() {
        val q = ListenQueue()
        q.set(songs(3), start = 1, shuffle = false)
        q.insertNext(listOf(MediaItem("n", "Next", ItemKind.AUDIO)))
        assertEquals(listOf("s1", "s2", "n", "s3"), ids(q))
        q.append(listOf(MediaItem("e", "End", ItemKind.AUDIO)))
        assertEquals(listOf("s1", "s2", "n", "s3", "e"), ids(q))
        q.remove(0)
        assertEquals("s2", q.current?.id)
        q.move(from = 3, to = 0)
        assertEquals(listOf("e", "s2", "n", "s3"), ids(q))
        assertEquals("s2", q.current?.id)
        q.remove(1)
        assertEquals("n", q.current?.id)
        assertEquals(0, q.previousPosition(Repeat.OFF))
    }

    @Test fun `replacing a title keeps the shuffled order`() {
        val q = ListenQueue(Random(3))
        q.set(songs(6), start = 2, shuffle = true)
        val before = ids(q)
        q.replace(MediaItem("s4", "Four, with chapters", ItemKind.AUDIO))
        assertEquals(before, ids(q))
        assertTrue(q.shuffle)
        assertEquals("Four, with chapters", q.playing().first { it.id == "s4" }.name)
    }
}

package dev.nitrate.core.writing

import dev.nitrate.core.writing.TextOperation.Delete
import dev.nitrate.core.writing.TextOperation.Insert
import dev.nitrate.core.writing.TextOperation.Retain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextOperationTest {
    private fun randomText(r: Random, max: Int = 30) =
        (0 until r.nextInt(max)).map { "abcdefg \nXY😀".let { s -> s[r.nextInt(s.length)] } }.joinToString("")
            // Random picks can split the emoji's surrogate pair: keep the text well formed.
            .filterNot { it.isSurrogate() }

    private fun randomOp(r: Random, text: String): TextOperation {
        val b = TextOperation.Builder()
        var left = text.length
        while (left > 0) {
            val n = 1 + r.nextInt(minOf(left, 5))
            when (r.nextInt(4)) {
                0 -> { b.retain(n); left -= n }
                1 -> { b.delete(n); left -= n }
                2 -> b.insert(randomText(r, 6).ifEmpty { "z" })
                else -> { b.retain(n); left -= n }
            }
        }
        if (r.nextBoolean()) b.insert(randomText(r, 4))
        return b.build()
    }

    @Test fun appliesInsertsDeletesAndKeeps() {
        val op = TextOperation.of(Retain(4), Insert("big "), Delete(4), Retain(3))
        assertEquals("the big dog", op.apply("the red dog"))
        assertEquals(11, op.baseLength)
        assertEquals(11, op.targetLength)
    }

    @Test fun diffFindsTheChangedStretch() {
        val old = "INT. KITCHEN - DAY\n\nSam pours coffee."
        val new = "INT. KITCHEN - NIGHT\n\nSam pours coffee."
        val op = TextOperation.diff(old, new)
        assertEquals(new, op.apply(old))
        assertEquals(TextOperation.of(Retain(15), Insert("NIGHT"), Delete(3), Retain(19)), op)
        assertTrue(TextOperation.diff(old, old).isNoop)
        // An emoji is never cut in half.
        val emojiOp = TextOperation.diff("a😀b", "a😃b")
        assertEquals("a😃b", emojiOp.apply("a😀b"))
        assertEquals(TextOperation.of(Retain(1), Insert("😃"), Delete(2), Retain(1)), emojiOp)
    }

    @Test fun concurrentEditsMeetInTheSameText() {
        val text = "Sam walks in."
        val a = TextOperation.diff(text, "Sam slowly walks in.")
        val b = TextOperation.diff(text, "Sam walks in. Jo looks up.")
        val (a2, b2) = TextOperation.transform(a, b)
        assertEquals("Sam slowly walks in. Jo looks up.", b2.apply(a.apply(text)))
        assertEquals(b2.apply(a.apply(text)), a2.apply(b.apply(text)))
    }

    @Test fun insertsAtTheSameSpotPutTheFirstOperationFirst() {
        val a = TextOperation.of(Retain(3), Insert("A"))
        val b = TextOperation.of(Retain(3), Insert("B"))
        val (a2, b2) = TextOperation.transform(a, b)
        assertEquals("xyzAB", a2.apply(b.apply("xyz")))
        assertEquals("xyzAB", b2.apply(a.apply("xyz")))
    }

    @Test fun randomConcurrentEditsAlwaysConverge() {
        val r = Random(42)
        repeat(3_000) {
            val text = randomText(r)
            val a = randomOp(r, text)
            val b = randomOp(r, text)
            val (a2, b2) = TextOperation.transform(a, b)
            assertEquals("$text / $a / $b", b2.apply(a.apply(text)), a2.apply(b.apply(text)))
        }
    }

    @Test fun composingIsTheSameAsApplyingInTurn() {
        val r = Random(7)
        repeat(3_000) {
            val text = randomText(r)
            val a = randomOp(r, text)
            val mid = a.apply(text)
            val b = randomOp(r, mid)
            assertEquals("$text / $a / $b", b.apply(mid), a.compose(b).apply(text))
        }
    }

    @Test fun cursorsFollowTheText() {
        val op = TextOperation.of(Retain(2), Insert("abc"), Retain(3), Delete(2), Retain(1))
        assertEquals(1, op.transformIndex(1))
        assertEquals(2, op.transformIndex(2))
        assertEquals(5, op.transformIndex(2, ownInsertsBefore = true))
        assertEquals(8, op.transformIndex(5))
        assertEquals(8, op.transformIndex(6))
        assertEquals(8, op.transformIndex(7))
        assertEquals(9, op.transformIndex(8))
    }

    @Test fun wireFormRoundTrips() {
        val op = TextOperation.of(Retain(5), Insert("hi"), Delete(3), Retain(2))
        assertEquals(listOf(5, "hi", -3, 2), TextOperation.toWire(op))
        assertEquals(op, TextOperation.fromWire(listOf(5, "hi", -3, 2)))
        assertEquals(TextOperation.of(Retain(4)), TextOperation.fromWire(listOf(2, 2L)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesTheWrongText() {
        TextOperation.of(Retain(3)).apply("four")
    }
}

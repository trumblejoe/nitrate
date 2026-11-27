package dev.nitrate.core.pages

import kotlin.test.Test
import kotlin.test.assertEquals

class TagChoicesTest {
    private val all = listOf("christmas", "film noir", "heist", "Neo-noir", "noir", "space opera", "time travel")
    private val counts = mapOf("heist" to 9, "time travel" to 14, "noir" to 3, "christmas" to 9)

    @Test
    fun chosenFirstThenMostUsedThenAToZ() {
        assertEquals(
            listOf("space opera", "time travel", "christmas", "heist", "noir", "film noir", "Neo-noir"),
            TagChoices.rank(all, counts, chosen = listOf("space opera")),
        )
    }

    @Test
    fun pagesOfTheRankedList() {
        val ranked = (1..50).map { "t$it" }
        assertEquals(3, TagChoices.pages(50))
        assertEquals((25..48).map { "t$it" }, TagChoices.page(ranked, 1))
        assertEquals(listOf("t49", "t50"), TagChoices.page(ranked, 2))
        assertEquals(1, TagChoices.pages(0))
    }

    @Test
    fun typingFindsStartsThenWordsThenAnywhere() {
        assertEquals(listOf("noir", "film noir", "Neo-noir"), TagChoices.matches(all, "noi", counts))
        assertEquals(listOf("time travel"), TagChoices.matches(all, "trav"))
        assertEquals(listOf("space opera"), TagChoices.matches(all, "pera"))
        assertEquals(emptyList(), TagChoices.matches(all, "  "))
    }
}

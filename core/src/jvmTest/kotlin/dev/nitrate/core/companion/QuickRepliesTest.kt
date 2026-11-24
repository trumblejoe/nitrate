package dev.nitrate.core.companion

import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRepliesTest {
    private val arena = MediaItem(
        "f1", "The Arena", ItemKind.MOVIE,
        overview = "A girl volunteers in her sister's place and must fight to the death in a televised competition.",
        genres = listOf("Science Fiction", "Adventure", "Action"),
        tags = listOf("archery", "dystopia"),
        people = listOf(
            Person("p1", "Ada Lane", "Kestrel Everwood", type = "Actor"),
            Person("p2", "Ben Hale", "Dr. Pell Marsh (voice)", type = "Actor"),
            Person("p3", "Cal Rook", "Himself", type = "Actor"),
            Person("p4", "Dee Moss", "", type = "Actor"),
            Person("p5", "Eve Sorn", "Theodore \"Teddy\" Gale", type = "Actor"),
            Person("p6", "Fay Dorn", "Director", type = "Director"),
            Person("p7", "Gus Pike", "Rue Pell", type = "Actor"),
        ),
    )

    @Test fun phrasesAreSentAsTheyAre() {
        assertEquals(listOf("No way!", "Called it", "Who's that?", "Wait, what?", "Love this part", "Pause?", "Ha!", "So good"), QuickReplies.PHRASES)
        assertEquals(QuickPicks.PLAIN, QuickReplies.forTitle(null))
        val plain = QuickReplies.forTitle(MediaItem("x", "Nothing known", ItemKind.MOVIE))
        assertEquals(QUICK_REACTIONS, plain.emojis)
        assertEquals(QuickReplies.PHRASES, plain.lines)
    }

    @Test fun charactersComeFromTheMainCastsRoles() {
        // Voice notes and titles go, "Himself" and empty roles are skipped, a quoted nickname wins; three at most.
        assertEquals(listOf("Kestrel", "Pell", "Teddy"), QuickReplies.names(arena))
        val picks = QuickReplies.forTitle(arena)
        assertEquals(listOf("Kestrel!", "Pell!", "Teddy!") + QuickReplies.PHRASES, picks.lines)
    }

    @Test fun rolesReadAsPeopleWouldCallThem() {
        assertEquals("Katniss", QuickReplies.characterName("Katniss Everdeen"))
        assertEquals("Gregory", QuickReplies.characterName("Dr. Gregory House"))
        assertEquals("Peeta", QuickReplies.characterName("Peeta Mellark / Young Peeta"))
        assertEquals("Ted", QuickReplies.characterName("Theodore 'Ted' Lasso"))
        assertEquals("The Doctor", QuickReplies.characterName("The Doctor"))
        assertEquals("Woody", QuickReplies.characterName("Woody (voice)"))
        assertNull(QuickReplies.characterName("Herself"))
        assertNull(QuickReplies.characterName("Narrator (voice)"))
        assertNull(QuickReplies.characterName("Guard #2"))
        assertNull(QuickReplies.characterName("Soldier (uncredited)"))
        assertNull(QuickReplies.characterName("   "))
    }

    @Test fun guestsAndCrewArentMainCastAndAnEpisodeUsesItsShowsCast() {
        val show = MediaItem("s", "Show", ItemKind.SERIES, people = listOf(Person("a", "Ann", "Mira Vale", type = "Actor")), tags = listOf("dragon"))
        val episode = MediaItem(
            "e", "Episode", ItemKind.EPISODE, seriesId = "s",
            people = listOf(Person("g", "Guest", "Odo Finch", type = "GuestStar"), Person("d", "Dir", "Director", type = "Director")),
        )
        assertEquals(listOf("Mira"), QuickReplies.names(episode, show))
        assertTrue(QuickReplies.names(episode).isEmpty())
        assertEquals(listOf("🐉"), QuickReplies.emojis(episode, show))
    }

    @Test fun theTitlesOwnEmojisComeFirstFromTagsThenOverviewThenGenres() {
        // archery (tag), death and competition (overview, in that order), then science fiction (genre).
        assertEquals(listOf("🏹", "💀", "🏆", "🚀"), QuickReplies.emojis(arena))
        val picks = QuickReplies.forTitle(arena)
        assertEquals(listOf("🏹", "💀", "🏆", "🚀"), picks.emojis.take(4))
        assertEquals(QuickReplies.MAX_EMOJIS, picks.emojis.size)
        assertEquals(QUICK_REACTIONS.take(QuickReplies.MAX_EMOJIS - 4), picks.emojis.drop(4))
    }

    @Test fun wordsMatchWholeAndInThePluralAndGenreWordsStayOutOfOverviews() {
        fun film(overview: String, genres: List<String> = emptyList()) = MediaItem("x", "x", ItemKind.MOVIE, overview = overview, genres = genres)
        assertEquals(listOf("🧟", "🦈"), QuickReplies.emojis(film("Zombies overrun a ship far out at sea.")))
        assertEquals(listOf("🍳", "💍"), QuickReplies.emojis(film("A chef caters her sister's wedding.")))
        // "scatter" holds "cat" but isn't one; "action" in an overview isn't the genre.
        assertTrue(QuickReplies.emojis(film("They scatter into action.")).isEmpty())
        assertEquals(listOf("💥"), QuickReplies.emojis(film("", listOf("Action"))))
        // Never one of the usual reactions again, and never more than four.
        assertEquals(QuickReplies.MAX_TITLE_EMOJIS, QuickReplies.emojis(film("A wizard, a dragon, a pirate, a robot and a vampire walk into a bar.")).size)
    }
}

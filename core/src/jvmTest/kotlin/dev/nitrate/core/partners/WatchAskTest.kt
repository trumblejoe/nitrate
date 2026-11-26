package dev.nitrate.core.partners

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asks to watch together: the Companion's news as the apps read it, and the words around them. */
class WatchAskTest {
    /** As the Companion's PartyPush sends it (PascalCase, an AskMessage). */
    private val news = """
        {"Kind":"ask","GroupId":"0f8fad5bd9cb469fa16570867728950e","SeriesName":"The Bear","Others":["Suzy"],"ByName":"Grandma","ByMe":false,"Minutes":0,"Party":null,
         "Event":"asked","Ask":{"Id":"7c9e6679742540de944be07fc1f90ae7","GroupId":"0f8fad5bd9cb469fa16570867728950e","ItemId":"e4","PartLabel":"S1:E4","Title":"The Bear",
         "FromName":"Grandma","FromMe":false,"At":"2026-11-26T21:30:00+00:00","State":"open","TakenBy":null,"TakenByMe":false,"Declined":[],"ServerTime":"2026-11-26T21:00:00+00:00"}}
    """.trimIndent()

    @Test fun theCompanionsNewsReadsAsAnAsk() {
        val n = AskNews.decode(news)!!
        assertEquals(AskNews.ASKED, n.event)
        assertEquals("Grandma", n.byName)
        assertEquals("7c9e6679-7425-40de-944b-e07fc1f90ae7", n.ask.id)
        assertEquals("S1:E4 of The Bear", n.ask.what)
        assertTrue(n.ask.open && n.ask.later)
        // Session and party news aren't asks.
        assertNull(AskNews.decode(news.replace("\"Kind\":\"ask\"", "\"Kind\":\"session\"")))
        assertNull(AskNews.decode("not json"))
    }

    @Test fun theQuestionSaysWhoWhatAndWhenAndTheAskerHearsHowItWent() {
        val later = AskNews.decode(news)!!.ask
        val clock = { _: Long -> "9:30" }
        assertEquals("Grandma would like to watch together" to "S1:E4 of The Bear · at 9:30, in 30 min. You'd host it: your device starts it then.", AskPrompt.question(later, clock))
        assertEquals("Start it at 9:30", AskPrompt.startLabel(later, clock))
        val now = later.copy(atMs = later.serverTimeMs)
        assertFalse(now.later)
        assertEquals("Start it", AskPrompt.startLabel(now, clock))
        assertTrue(AskPrompt.question(now, clock).second.endsWith("now. You'd host it: your device starts it and they join."))

        assertEquals("Suzy is starting S1:E4 of The Bear: you'll be asked to join", AskPrompt.toAsker(AskNews(AskNews.TAKEN, now.copy(state = WatchAsk.TAKEN), "Suzy")))
        assertEquals("Suzy can't right now", AskPrompt.toAsker(AskNews(AskNews.DECLINED, now, "Suzy")))
        assertEquals("Nobody can watch S1:E4 of The Bear with you right now", AskPrompt.toAsker(AskNews(AskNews.DECLINED, now.copy(state = WatchAsk.DECLINED), "Kim")))
        assertNull(AskPrompt.toAsker(AskNews(AskNews.ASKED, now, "Grandma")))
        assertEquals(listOf("Now", "In 15 minutes", "In 30 minutes", "In an hour"), WatchAsk.WHEN.map(WatchAsk::whenLabel))
    }
}

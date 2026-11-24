package dev.nitrate.core.partners

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupWatchSessionTest {
    private val t0 = 1_800_000_000_000L

    private fun session(
        state: String = GroupWatchSession.PLAYING,
        startAt: Long? = t0,
        position: Long = 0,
        paused: Boolean = false,
        serverTime: Long = t0,
        members: List<SessionMember> = listOf(
            SessionMember("tommy", "Tommy", me = false, positionMs = position, leader = true),
            SessionMember("me", "Me", me = true, positionMs = position),
        ),
        joined: Boolean = true,
    ) = GroupWatchSession(
        "s1", "g1", "ep4", "S1:E4", "The Bear", "Tommy", false, state, startAt, 0, position, paused, t0 + 60_000, members, joined, serverTime,
    )

    @Test
    fun theClockTakesTheServersTimeAsTheMiddleOfTheShortestRoundTrip() {
        val clock = SessionClock()
        // Asked at 1000, answered at 1200 (this device); the server said 5100: it's 5000 ahead.
        clock.sample(serverMs = 5_100, sentMs = 1_000, receivedMs = 1_200)
        assertEquals(4_000, clock.offsetMs)
        // A slower answer doesn't replace a quicker one.
        clock.sample(serverMs = 9_000, sentMs = 2_000, receivedMs = 3_000)
        assertEquals(4_000, clock.offsetMs)
        // A quicker one does.
        clock.sample(serverMs = 7_020, sentMs = 3_000, receivedMs = 3_040)
        assertEquals(4_000, clock.offsetMs)
        assertEquals(10_000, clock.serverAt(6_000))
        assertEquals(6_000, clock.localAt(10_000))
    }

    @Test
    fun everyoneStartsAtTheMomentFromTheSamePosition() {
        val s = session(state = GroupWatchSession.STARTING, startAt = t0 + 5_000)
        assertEquals(5, SessionSync.countdown(s, t0))
        assertEquals(1, SessionSync.countdown(s, t0 + 4_200))
        assertNull(SessionSync.countdown(s, t0 + 5_000))
        assertEquals(0, SessionSync.groupPosition(s, t0 + 3_000))
        assertEquals(2_000, SessionSync.groupPosition(s, t0 + 7_000))
        // Gathering: no moment yet.
        assertNull(SessionSync.countdown(session(state = GroupWatchSession.WAITING, startAt = null), t0))
    }

    @Test
    fun laterJoinersStartWhereTheGroupIsOrFromTheBeginning() {
        val gathering = session(state = GroupWatchSession.WAITING, startAt = null)
        assertEquals(0, SessionSync.joinAt(gathering, t0 + 30_000, fromStart = false))
        // Started at t0, at 61 s when the server answered 61 s in; 9 s later the group is at 70 s.
        val playing = session(position = 61_000, serverTime = t0 + 61_000)
        assertEquals(70_000, SessionSync.joinAt(playing, t0 + 70_000, fromStart = false))
        assertEquals(0, SessionSync.joinAt(playing, t0 + 70_000, fromStart = true))
        // Paused, the group's position stands.
        assertEquals(61_000, SessionSync.groupPosition(playing.copy(paused = true), t0 + 90_000))
    }

    @Test
    fun moreThanTenSecondsFromTheLeaderOffersToCatchUp() {
        val s = session(position = 100_000, serverTime = t0 + 100_000)
        // 5 s behind: close enough.
        assertNull(SessionSync.drift(s, myPositionMs = 95_000, myPaused = false, serverNowMs = t0 + 100_000))
        // 12 s behind Tommy.
        val behind = assertNotNull(SessionSync.drift(s, 98_000, false, t0 + 110_000))
        assertTrue(behind.behind)
        assertEquals("12 s behind Tommy", behind.text)
        assertEquals("Catch up", behind.action)
        assertEquals(110_000, behind.targetMs)
        // 15 s ahead.
        val ahead = assertNotNull(SessionSync.drift(s, 115_500, false, t0 + 100_000))
        assertEquals("15 s ahead of Tommy", ahead.text)
        assertEquals("Go back to Tommy", ahead.action)
        // Paused (either side), or the leader being the user: nothing.
        assertNull(SessionSync.drift(s, 50_000, true, t0 + 100_000))
        val pausedLeader = s.copy(members = s.members.map { if (it.leader) it.copy(paused = true) else it })
        assertNull(SessionSync.drift(pausedLeader, 50_000, false, t0 + 100_000))
        val meLeading = s.copy(members = s.members.map { it.copy(leader = it.me) })
        assertNull(SessionSync.drift(meLeading, 50_000, false, t0 + 100_000))
        // Before the start, nothing either.
        assertNull(SessionSync.drift(session(state = GroupWatchSession.STARTING, startAt = t0 + 5_000), 50_000, false, t0))
    }

    @Test
    fun withoutALeaderThePaceIsTheMiddleOfTheOthers() {
        val s = session(
            position = 100_000, serverTime = t0 + 100_000,
            members = listOf(
                SessionMember("a", "Ann", false, 80_000),
                SessionMember("b", "Ben", false, 100_000),
                SessionMember("c", "Cat", false, 140_000),
                SessionMember("me", "Me", true, 100_000),
            ),
        )
        assertEquals("Ben", SessionSync.reference(s, t0 + 100_000)?.name)
        assertNull(SessionSync.drift(s, 100_000, false, t0 + 100_000))
    }

    @Test
    fun othersPausesAndJumpsAreOfferedNeverDone() {
        fun news(event: String, byMe: Boolean = false, position: Long = 1_450_000) =
            SessionNews(event, "g1", "The Bear", listOf("Sam"), "Sam", byMe, position, session())
        assertEquals("Sam paused", SessionSync.noticeOf(news(SessionNews.PAUSE))!!.text)
        assertEquals("Pause too", SessionSync.noticeOf(news(SessionNews.PAUSE))!!.action)
        val jumped = assertIs<SessionNotice.Jumped>(SessionSync.noticeOf(news(SessionNews.SEEK)))
        assertEquals("Sam jumped to 24:10", jumped.text)
        assertEquals("Jump too", jumped.action)
        assertEquals("Play too", SessionSync.noticeOf(news(SessionNews.PLAY))!!.action)
        assertEquals("Sam joined", SessionSync.noticeOf(news(SessionNews.JOINED))!!.text)
        assertNull(SessionSync.noticeOf(news(SessionNews.PAUSE, byMe = true)))
        assertNull(SessionSync.noticeOf(news(SessionNews.COUNTDOWN)))
        assertEquals("Sam ended the Group Watch Session. You carry on by yourself.", SessionSync.noticeOf(news(SessionNews.ENDED))!!.text)
    }

    @Test
    fun theJoinPromptSaysWhoStartedItAndOffersTheBeginningToLateJoiners() {
        val gathering = session(state = GroupWatchSession.WAITING, startAt = null, joined = false, members = listOf(SessionMember("tommy", "Tommy", false, 0, leader = true)))
        val p = assertNotNull(SessionPrompt.of(gathering, t0))
        assertEquals("Group Watch Session", p.title)
        assertEquals("Tommy started the session · S1:E4 of The Bear", p.text)
        assertEquals(listOf("Join", "Not now"), p.choices.map { it.label })

        val late = session(position = 750_000, serverTime = t0 + 750_000, joined = false)
        val q = assertNotNull(SessionPrompt.of(late, t0 + 750_000))
        assertEquals(listOf("Join at 12:30", "Start from the beginning", "Not now"), q.choices.map { it.label })
        assertEquals(listOf("Join at 12:30", "Not now"), SessionPrompt.of(late, t0 + 750_000, easy = true)!!.choices.map { it.label })

        // Nothing once in it, or once it's over.
        assertNull(SessionPrompt.of(late.copy(joined = true), t0))
        assertNull(SessionPrompt.of(late.copy(state = GroupWatchSession.ENDED), t0))
    }

    @Test
    fun theStarterSeesWhoItWaitsFor() {
        val group = WatchGroup("g1", "show", "The Bear", listOf(dev.nitrate.core.writing.UserRef("tommy", "Tommy"), dev.nitrate.core.writing.UserRef("suzy", "Suzy")), 0L)
        val s = session(state = GroupWatchSession.WAITING, startAt = null, members = listOf(SessionMember("me", "Me", true, 0, leader = true), SessionMember("tommy", "Tommy", false, 0)))
        assertEquals("Waiting for Suzy to join", SessionPrompt.waiting(s, group))
        assertNull(SessionPrompt.waiting(s.copy(state = GroupWatchSession.PLAYING), group))
    }

    @Test
    fun newsIsReadFromTheCompanionsMessage() {
        val text = """{"Kind":"session","GroupId":"0f8fad5bd9cb469fa16570867728950e","SeriesName":"The Bear","Others":["Sam"],"ByName":"Sam","ByMe":false,"Minutes":0,"Party":null,
            |"Event":"pause","PositionMs":61000,"Session":{"Id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","GroupId":"0f8fad5b-d9cb-469f-a165-70867728950e","ItemId":"a5b4a3b2-0000-0000-0000-000000000001",
            |"PartLabel":"S1:E4","Title":"The Bear","StartedBy":"Sam","StartedByMe":false,"State":"playing","StartAt":"2026-11-19T20:00:05+00:00","StartMs":0,"PositionMs":61000,"Paused":true,
            |"GatherUntil":"2026-11-19T20:01:00+00:00","Members":[{"Id":"0f8fad5b-d9cb-469f-a165-70867728950f","Name":"Sam","Me":false,"PositionMs":61000,"Paused":true,"Leader":true,"Together":true}],
            |"Joined":true,"ServerTime":"2026-11-19T20:01:06+00:00"}}""".trimMargin()
        val news = assertNotNull(SessionNews.decode(text))
        assertEquals(SessionNews.PAUSE, news.event)
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", news.groupId)
        assertEquals(61_000, news.positionMs)
        val s = news.session
        assertEquals("7c9e6679-7425-40de-944b-e07fc1f90ae7", s.id)
        assertEquals(kotlin.time.Instant.parse("2026-11-19T20:00:05Z").toEpochMilliseconds(), s.startAtMs)
        assertEquals(kotlin.time.Instant.parse("2026-11-19T20:01:06Z").toEpochMilliseconds(), s.serverTimeMs)
        assertTrue(s.members.single().leader)
        // Party and schedule news aren't session news, and session news isn't schedule news.
        assertNull(SessionNews.decode("""{"Kind":"reminder","GroupId":"0f8fad5bd9cb469fa16570867728950e"}"""))
        assertNull(ScheduleNews.decode(text))
    }
}

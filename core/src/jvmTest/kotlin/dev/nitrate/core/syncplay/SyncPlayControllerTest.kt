package dev.nitrate.core.syncplay

import dev.nitrate.core.MediaServer
import dev.nitrate.core.events.ServerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncPlayControllerTest {
    /** The server's clock runs [SERVER_AHEAD_MS] ahead of ours. */
    private class Fake(private val scope: TestScope) {
        val sent = mutableListOf<SyncPlayRequest>()
        val server: MediaServer = java.lang.reflect.Proxy.newProxyInstance(
            MediaServer::class.java.classLoader,
            arrayOf(MediaServer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "syncPlay" -> sent += args[0] as SyncPlayRequest
                "serverTime" -> return@newProxyInstance (scope.testScheduler.currentTime + SERVER_AHEAD_MS).let { ServerTimeSample(it, it) }
                "syncPlayJoin", "syncPlayCreate", "syncPlayLeave" -> Unit
                else -> error("not used: ${method.name}")
            }
            Unit
        } as MediaServer
    }

    private class Player(private val scope: TestScope) : SyncPlayer {
        override var itemId: String? = null
        var startedAt = 0L
        var base = 0L
        override var isPlaying = false
        override var isReady = false
        var speed = 1f
        val seeks = mutableListOf<Long>()
        override val positionMs: Long get() = if (isPlaying) base + ((scope.testScheduler.currentTime - startedAt) * speed).toLong() else base
        override fun load(itemId: String, startMs: Long) {
            this.itemId = itemId
            base = startMs
            isReady = false
        }
        override fun play() {
            base = positionMs
            startedAt = scope.testScheduler.currentTime
            isPlaying = true
        }
        override fun pause() {
            base = positionMs
            isPlaying = false
        }
        override fun seek(positionMs: Long) {
            seeks += positionMs
            base = positionMs
            startedAt = scope.testScheduler.currentTime
        }
        override fun setRate(rate: Float) {
            base = positionMs
            startedAt = scope.testScheduler.currentTime
            speed = rate
        }
        override fun stop() = Unit
    }

    private val queue = SyncPlayQueue("NewPlaylist", listOf("movie"), listOf("p1"), 0, 60_000, isPlaying = false)

    private fun group(state: GroupState = GroupState.PAUSED) = ServerEvent.SyncPlayGroupUpdate("g", SyncPlayUpdate.Joined(SyncPlayGroup("g", "Movie night", state, listOf("joe"))))

    @Test
    fun `measures the server clock from the best round trip`() {
        val m = ClockMeasurement.of(sentMs = 1_000, sample = ServerTimeSample(6_050, 6_060), receivedMs = 1_100)
        assertEquals(5_005L, m.offsetMs)
        assertEquals(90L, m.roundTripMs)
        assertEquals(10L, ClockMeasurement.best(listOf(m, ClockMeasurement(1, 10)))!!.roundTripMs)
    }

    @Test
    fun `drift is fixed by rate when small and by seeking when large`() {
        assertEquals(DriftFix.None, DriftFix.of(10_050, 10_000))
        assertEquals(DriftFix.Rate(0.95f), DriftFix.of(10_500, 10_000))
        assertEquals(DriftFix.Rate(1.05f), DriftFix.of(9_500, 10_000))
        assertEquals(DriftFix.Seek(10_000), DriftFix.of(13_000, 10_000))
    }

    @Test
    fun `follows the group queue, reports ready, and plays when told`() = runTest {
        val fake = Fake(this)
        val c = SyncPlayController(fake.server, backgroundScope) { testScheduler.currentTime }
        val player = Player(this)
        c.handle(group())
        runCurrent()
        assertEquals(SERVER_AHEAD_MS, c.clockOffsetMs)

        // Opening the player on another item asks the group to switch to it.
        c.attach(player, "other", 0)
        runCurrent()
        assertEquals(SyncPlayRequest.SetQueue(listOf("other"), 0, 0), fake.sent.last())

        // The group's queue arrives: load paused at its start, and say when ready.
        c.handle(ServerEvent.SyncPlayGroupUpdate("g", SyncPlayUpdate.Queue(queue)))
        assertEquals("movie", player.itemId)
        assertEquals(60_000L, player.positionMs)
        player.isReady = true
        c.onPlayerState()
        runCurrent()
        val ready = fake.sent.last()
        assertIs<SyncPlayRequest.Status>(ready)
        assertEquals(false, ready.buffering)
        assertEquals("p1", ready.playlistItemId)

        // Unpause in 500 ms (server clock): nothing until then, then play from 60 s.
        val at = testScheduler.currentTime + SERVER_AHEAD_MS + 500
        c.handle(ServerEvent.SyncPlayGroupUpdate("g", SyncPlayUpdate.State(GroupState.PLAYING, "Unpause")))
        c.handle(ServerEvent.SyncPlayCommand("g", SyncPlayCommand("Unpause", at, 60_000, "p1")))
        advanceTimeBy(400)
        assertEquals(false, player.isPlaying)
        advanceTimeBy(200)
        assertTrue(player.isPlaying)
        assertTrue(player.seeks.isEmpty(), "already in place: no seek (it would make everyone wait)")

        // Running slow: sped up a little to catch up.
        player.base -= 400
        advanceTimeBy(1_100)
        assertEquals(1.05f, player.speed)

        // Pause from someone else: pauses at the given spot.
        c.handle(ServerEvent.SyncPlayCommand("g", SyncPlayCommand("Pause", testScheduler.currentTime + SERVER_AHEAD_MS, 90_000, "p1")))
        runCurrent()
        assertEquals(false, player.isPlaying)
        assertEquals(90_000L, player.positionMs)
        assertEquals(1f, player.speed)
    }

    @Test
    fun `joins late by skipping ahead and reports stalls`() = runTest {
        val fake = Fake(this)
        val c = SyncPlayController(fake.server, backgroundScope) { testScheduler.currentTime }
        val player = Player(this)
        c.handle(group(GroupState.PLAYING))
        runCurrent()
        c.handle(ServerEvent.SyncPlayGroupUpdate("g", SyncPlayUpdate.Queue(queue)))
        c.attach(player, "movie", 0)
        player.isReady = true
        c.onPlayerState()
        runCurrent()

        // The command was meant for 3 s ago: start 3 s further in.
        c.handle(ServerEvent.SyncPlayCommand("g", SyncPlayCommand("Unpause", testScheduler.currentTime + SERVER_AHEAD_MS - 3_000, 60_000, "p1")))
        runCurrent()
        assertEquals(listOf(63_000L), player.seeks)
        assertTrue(player.isPlaying)

        // A stall well after that seek tells the group to wait.
        advanceTimeBy(5_000)
        player.isReady = false
        c.onPlayerState()
        runCurrent()
        assertEquals(true, (fake.sent.last() as SyncPlayRequest.Status).buffering)
    }

    @Test
    fun `opens a player when the group starts something`() = runTest {
        val fake = Fake(this)
        val c = SyncPlayController(fake.server, backgroundScope) { testScheduler.currentTime }
        val opened = mutableListOf<SyncPlayQueue>()
        backgroundScope.launchCollect(c, opened)
        c.handle(group())
        runCurrent()
        c.handle(ServerEvent.SyncPlayGroupUpdate("g", SyncPlayUpdate.Queue(queue)))
        runCurrent()
        assertEquals("movie", opened.single().currentItemId)

        c.handle(ServerEvent.SyncPlayGroupUpdate("g", SyncPlayUpdate.Left))
        assertEquals(null, c.group.value)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(c: SyncPlayController, into: MutableList<SyncPlayQueue>) =
        launch(kotlinx.coroutines.Dispatchers.Unconfined) { c.openRequests.collect { into += it } }

    private companion object {
        const val SERVER_AHEAD_MS = 5_000L
    }
}

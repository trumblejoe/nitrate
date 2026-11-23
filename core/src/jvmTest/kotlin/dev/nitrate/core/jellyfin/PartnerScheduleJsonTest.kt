package dev.nitrate.core.jellyfin

import dev.nitrate.core.partners.GroupSchedule
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A group's schedule as the Companion plugin sends and keeps it: "How we watch" rides along, and older schedules read as separately. */
class PartnerScheduleJsonTest {
    private val server = "http://jf.example:8096"
    private val sent = mutableListOf<String>()
    private val g1 = "1".repeat(32)
    private val g2 = "2".repeat(32)
    private val show = "a".repeat(32)
    private val ep3 = "c".repeat(32)

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        (request.body as? OutgoingContent.ByteArrayContent)?.let { synchronized(sent) { sent += path + " " + it.bytes().decodeToString() } }
        val body = when (path) {
            // One group from before the choice (no "Together"), one that watches together only.
            "/Nitrate/Partners" -> """{"Groups":[
                {"Id":"$g1","SeriesId":"$show","SeriesName":"Bear","Others":[{"Id":"${"5".repeat(32)}","Name":"Sam"}],
                 "Schedule":{"Cadence":"weekly","Day":4,"Hour":19,"Minute":0,"SoftLock":false,"TimeZone":""}},
                {"Id":"$g2","SeriesId":"$show","SeriesName":"Bear","Others":[{"Id":"${"6".repeat(32)}","Name":"Kim"}],
                 "Schedule":{"Cadence":"binge","Day":4,"Hour":19,"Minute":0,"SoftLock":true,"Together":true},"TogetherFromId":"$ep3"}
            ]}"""
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession(server, "token", "user-1", "sam"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    @Test fun howWeWatchIsReadWithTheSchedule() = runBlocking {
        val groups = client().partners().groups
        val old = groups.first { it.id == normalizeId(g1) }
        assertFalse(old.schedule!!.together)
        assertNull(old.togetherFromId)
        assertEquals("Separately, at the group's pace", old.schedule!!.how)
        val together = groups.first { it.id == normalizeId(g2) }
        assertTrue(together.schedule!!.together)
        assertTrue(together.schedule!!.softLock)
        assertEquals(normalizeId(ep3), together.togetherFromId)
        assertEquals("Binge, together only, ask first", together.schedule!!.label)
    }

    @Test fun howWeWatchIsSentWithAChange() = runBlocking {
        val c = client()
        c.proposeSchedule(normalizeId(g1), GroupSchedule(GroupSchedule.WEEKLY, together = true))
        c.proposeSchedule(normalizeId(g1), GroupSchedule(GroupSchedule.WEEKLY))
        val bodies = sent.filter { it.contains("/Schedule ") }
        assertEquals(2, bodies.size)
        assertTrue(bodies[0], bodies[0].contains("\"Together\":true"))
        // Separately is the plugin's default, so it needn't be said.
        assertFalse(bodies[1], bodies[1].contains("\"Together\":true"))
    }
}

package dev.nitrate.core.jellyfin

import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home and away: the client uses whichever of the server's addresses answers, local first. */
class ServerAddressesTest {
    private val home = "http://192.168.1.20:8096"
    private val away = "https://media.example.net"

    /** Hosts that answer, and the server id each reports. */
    private var up = mutableMapOf(home to "srv", away to "srv")

    private val engine = MockEngine { request ->
        val host = "${request.url.protocol.name}://${request.url.host}" + (if (request.url.specifiedPort > 0 && request.url.specifiedPort != request.url.protocol.defaultPort) ":${request.url.port}" else "")
        val id = up[host] ?: run { delay(10_000); error("unreachable") }
        val body = when (request.url.encodedPath) {
            "/System/Info/Public" -> """{"Id":"$id","ServerName":"Home server"}"""
            else -> """{"Id":"user-1","Name":"sam"}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private val storage = MemorySessionStorage(listOf(StoredSession(home, "token", "user-1", "sam", localUrl = home, remoteUrl = away)))
    private val client = JellyfinClient(engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"), storage, { caps() }, settings)

    @Test fun localFirstAtHome() = runBlocking {
        assertEquals(SessionState.VALID, client.restoreSession())
        assertEquals(home, client.serverUrl)
    }

    @Test fun remoteWhenAway() = runBlocking {
        client.restoreSession()
        // Out of the house: the home address stops answering.
        up.remove(home)
        assertEquals(SessionState.VALID, client.reconnect())
        assertEquals(away, client.serverUrl)
        // Back home: local again.
        up[home] = "srv"
        client.reconnect()
        assertEquals(home, client.serverUrl)
    }

    @Test fun neverAnotherServer() = runBlocking {
        client.restoreSession()
        // The away address now leads to someone else's server: it isn't used.
        up.remove(home)
        up[away] = "other"
        client.reconnect()
        assertEquals(home, client.serverUrl)
        val check = client.testAddress(away)
        assertTrue(check is AddressCheck.Reached && !check.sameServer)
    }

    @Test fun savingAddresses() = runBlocking {
        client.restoreSession()
        assertEquals(SessionState.VALID, client.saveServerAddresses(local = "192.168.1.20:8096 ", remote = "https://media.example.net/"))
        val saved = client.serverAddresses()!!
        assertEquals(home, saved.local)
        assertEquals(away, saved.remote)
        assertEquals(home, storage.load()!!.localUrl)
        assertTrue(client.testAddress("nowhere.invalid") is AddressCheck.Failed)
    }
}

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home, VPN and away: the client uses whichever of a server's addresses answers, in that order. */
class ServerAddressesTest {
    private val home = "http://192.168.1.20:8096"
    private val vpn = "http://100.64.0.7:8096"
    private val away = "https://media.example.net"
    private val cabin = "http://cabin.lan:8096"

    /** Hosts that answer, and the server id each reports. */
    private val up = mutableMapOf(home to "srv", vpn to "srv", away to "srv", cabin to "cabin")

    private val engine = MockEngine { request ->
        val port = request.url.specifiedPort.takeIf { it > 0 && it != request.url.protocol.defaultPort }
        val host = "${request.url.protocol.name}://${request.url.host}" + (port?.let { ":$it" } ?: "")
        val id = up[host] ?: run { delay(10_000); error("unreachable") }
        val body = when (request.url.encodedPath) {
            "/System/Info/Public" -> """{"Id":"$id","ServerName":"${if (id == "srv") "Home server" else "Cabin"}"}"""
            else -> """{"Id":"user-1","Name":"sam"}"""
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private val storage = MemorySessionStorage(
        listOf(
            StoredSession(home, "token", "user-1", "sam", localUrl = home, vpnUrl = vpn, remoteUrl = away),
            StoredSession(cabin, "token2", "user-2", "sam"),
        ),
    )

    private fun client() = JellyfinClient(engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"), storage, { caps() }, settings)

    @Test fun localFirstAtHome() = runBlocking {
        val client = client()
        assertEquals(SessionState.VALID, client.restoreSession())
        assertEquals(home, client.serverUrl)
    }

    @Test fun vpnThenRemoteWhenAway() = runBlocking {
        val client = client()
        client.restoreSession()
        // Out of the house with the VPN on.
        up.remove(home)
        assertEquals(SessionState.VALID, client.reconnect())
        assertEquals(vpn, client.serverUrl)
        // VPN off: the public address.
        up.remove(vpn)
        client.reconnect()
        assertEquals(away, client.serverUrl)
        // Back home: local again.
        up[home] = "srv"
        client.reconnect()
        assertEquals(home, client.serverUrl)
    }

    @Test fun neverAnotherServer() = runBlocking {
        val client = client()
        client.restoreSession()
        // The other addresses now lead to someone else's server: they aren't used.
        up.remove(home)
        up[vpn] = "other"
        up[away] = "other"
        client.reconnect()
        assertEquals(home, client.serverUrl)
        val check = client.testAddress(away)
        assertTrue(check is AddressCheck.Reached && !check.sameServer)
    }

    @Test fun savingAddresses() = runBlocking {
        val client = client()
        client.restoreSession()
        assertEquals(SessionState.VALID, client.saveServerAddresses(home, local = "192.168.1.20:8096 ", vpn = "", remote = "https://media.example.net/"))
        val saved = client.servers().first { it.url == home }
        assertEquals(home, saved.local)
        assertEquals(null, saved.vpn)
        assertEquals(away, saved.remote)
        assertTrue(saved.isCurrent)
        assertTrue(client.testAddress("nowhere.invalid") is AddressCheck.Failed)
    }

    @Test fun severalServersAndPrimary() = runBlocking {
        val client = client()
        client.restoreSession()
        assertEquals(listOf(home, cabin), client.servers().map { it.url })
        // Addresses for a server that isn't current: saved, and the current one stays.
        client.saveServerAddresses(cabin, local = cabin, vpn = null, remote = null)
        assertEquals(cabin, client.servers().first { it.url == cabin }.local)
        assertEquals(home, client.serverUrl)
        assertEquals("$home|user-1", storage.load()!!.key)
        // The cabin becomes primary: it's listed first and opened at the next start.
        client.setPrimaryServer(cabin)
        assertEquals(cabin, client.servers().first().url)
        assertTrue(client.servers().first().primary)
        assertFalse(client.servers().last().primary)
        val restarted = client()
        assertEquals(SessionState.VALID, restarted.restoreSession())
        assertEquals(cabin, restarted.serverUrl)
    }
}

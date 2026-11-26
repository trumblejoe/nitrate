package dev.nitrate.core.jellyfin

import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.playback.caps
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** A found image's size read from the start of its file, asked of the provider with nothing of the user's sent. */
class RemoteImageSizeTest {
    private val seen = mutableListOf<Headers>()
    private val asked = mutableListOf<String>()

    private val engine = MockEngine { request ->
        asked += request.url.toString()
        seen += request.headers
        when (request.url.host) {
            "assets.fanart.tv" -> {
                // A PNG's header: 800×310.
                val png = ByteArray(64)
                byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(png)
                png[16] = 0; png[17] = 0; png[18] = 0x03; png[19] = 0x20.toByte()
                png[20] = 0; png[21] = 0; png[22] = 0x01; png[23] = 0x36
                respond(png, HttpStatusCode.PartialContent)
            }
            else -> respond("nope", HttpStatusCode.NotFound)
        }
    }

    private val settings = object : PlaybackSettingsRepository {
        override val settings = MutableStateFlow(PlaybackSettings())
        override suspend fun current() = settings.value
        override suspend fun update(transform: (PlaybackSettings) -> PlaybackSettings) { settings.value = transform(settings.value) }
    }

    private fun client() = JellyfinClient(
        engine, ClientIdentity("Nitrate", "1.0", "dev1", "Test"),
        MemorySessionStorage(listOf(StoredSession("http://jf.example:8096", "token", "user1", "sam"))), { caps() }, settings,
    ).also { runBlocking { it.restoreSession() } }

    @Test fun theSizeIsReadFromTheFileAndNothingOfOursIsSent() = runBlocking {
        val c = client()
        assertEquals(800 to 310, c.remoteImageSize("https://assets.fanart.tv/fanart/movies/1/hdmovielogo/logo.png"))
        val h = seen.last()
        assertEquals("bytes=0-262143", h["Range"])
        assertNull(h["Authorization"])
        assertNull(h["X-Emby-Authorization"])
        assertFalse(h.names().any { it.contains("Nitrate", ignoreCase = true) || it.contains("Emby", ignoreCase = true) })
        // Read once.
        c.remoteImageSize("https://assets.fanart.tv/fanart/movies/1/hdmovielogo/logo.png")
        assertEquals(1, asked.count { it.contains("fanart") })
        // Unreadable or not a web address: none.
        assertNull(c.remoteImageSize("https://image.example.org/missing.png"))
        assertNull(c.remoteImageSize("file:///etc/passwd"))
    }
}

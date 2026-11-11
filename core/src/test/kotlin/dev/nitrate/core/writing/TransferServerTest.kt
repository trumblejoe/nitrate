package dev.nitrate.core.writing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

private class FakeDocs : TransferDocs {
    val texts = linkedMapOf("a1" to "Title: First\n\nINT. ROOM - DAY\n", "b2" to "Second draft\n")
    override fun list() = texts.map { (id, text) -> WritingDoc(id, Scripts.titleOf(text), 1_700_000_000_000) }
    override suspend fun read(id: String) = texts[id]
    override suspend fun create(text: String) = "new${texts.size}".also { texts[it] = text }
}

class TransferServerTest {
    private fun request(raw: String) = HttpRequest.read(ByteArrayInputStream(raw.toByteArray()))

    @Test
    fun `parses method, path, query, headers and body`() {
        val req = request("POST /api/upload?pin=123%20456&x=1 HTTP/1.1\r\nHost: tv\r\nX-Nitrate-Pin: 999\r\nContent-Length: 5\r\n\r\nhello")!!
        assertEquals("POST", req.method)
        assertEquals("/api/upload", req.path)
        assertEquals("123 456", req.query["pin"])
        assertEquals("999", req.headers["x-nitrate-pin"])
        assertEquals("hello", req.body.decodeToString())
    }

    @Test
    fun `accepts an upload right at the limit`() {
        val body = ByteArray(HttpRequest.MAX_BODY) { 'a'.code.toByte() }
        val head = "POST /api/upload HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()
        val req = HttpRequest.read(ByteArrayInputStream(head + body))!!
        assertEquals(25 * 1024 * 1024, req.body.size)
    }

    @Test
    fun `rejects oversized, truncated and malformed requests`() {
        assertNull(request("POST / HTTP/1.1\r\nContent-Length: ${HttpRequest.MAX_BODY + 1}\r\n\r\n"))
        assertNull(request("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
        assertNull(request("garbage"))
        assertNull(request("GET / HTTP/1.1\r\n" + "X: ".padEnd(20_000, 'a')))
    }

    private val docs = FakeDocs()
    private val handler = TransferHandler(docs, "424242")

    private fun call(method: String, path: String, pin: String? = "424242", body: ByteArray = ByteArray(0)) = runBlocking {
        handler.handle(HttpRequest(method, path, emptyMap(), pin?.let { mapOf("x-nitrate-pin" to it) }.orEmpty(), body))
    }

    @Test
    fun `the page is public but every API call needs the PIN`() {
        assertEquals(200, call("GET", "/", pin = null).status)
        assertEquals(403, call("GET", "/api/docs", pin = null).status)
        assertEquals(403, call("GET", "/api/docs", pin = "000000").status)
        assertEquals(200, call("GET", "/api/docs").status)
    }

    @Test
    fun `lists scripts and downloads them in either format`() {
        val list = call("GET", "/api/docs").body.decodeToString()
        assertTrue(list.contains(""""id":"a1","title":"First""""))
        val fountain = call("GET", "/api/docs/a1.fountain")
        assertEquals("INT. ROOM - DAY", fountain.body.decodeToString().lines()[2])
        assertEquals("attachment; filename=\"First.fountain\"", fountain.headers["Content-Disposition"])
        val fadein = call("GET", "/api/docs/a1.fadein")
        assertEquals('P'.code.toByte(), fadein.body[0])
        assertTrue(FadeIn.import(fadein.body).contains("INT. ROOM - DAY"))
        assertEquals(404, call("GET", "/api/docs/zz.fountain").status)
        assertEquals(404, call("GET", "/api/docs/../secret.fountain").status)
    }

    @Test
    fun `uploads fountain text and fadein files as new scripts`() {
        val fromText = call("POST", "/api/upload", body = "EXT. PIER - DAWN\n".toByteArray())
        assertEquals(200, fromText.status)
        val fromFadeIn = call("POST", "/api/upload", body = FadeIn.export("Title: Pier\n\nEXT. PIER - DAWN\n"))
        assertTrue(fromFadeIn.body.decodeToString().contains(""""title":"Pier""""))
        assertTrue(docs.texts.values.any { it.startsWith("Title: Pier") })
        assertEquals(400, call("POST", "/api/upload").status)
    }

    @Test
    fun `wrong PINs are reported to the server so it can lock out`() {
        var bad = 0
        val h = TransferHandler(docs, "424242") { bad++ }
        runBlocking { repeat(3) { h.handle(HttpRequest("GET", "/api/docs", mapOf("pin" to "1"), emptyMap(), ByteArray(0))) } }
        assertEquals(3, bad)
    }

    @Test
    fun `serves over a real socket and turns off after too many wrong PINs`() {
        val server = TransferServer(FakeDocs())
        server.start("127.0.0.1")
        try {
            val state = server.state.value
            assertTrue(state.running)
            val base = state.address!!
            fun get(path: String): Pair<Int, String> {
                val c = URL(base + path).openConnection() as HttpURLConnection
                return try { c.responseCode to (if (c.responseCode < 400) c.inputStream else c.errorStream).readBytes().decodeToString() } finally { c.disconnect() }
            }
            assertTrue(get("/").second.contains("Nitrate scripts"))
            assertEquals(200, get("/api/docs?pin=${state.pin}").first)
            repeat(TransferServer.MAX_BAD_PINS) { assertEquals(403, get("/api/docs?pin=0").first) }
            Thread.sleep(200)
            assertFalse(server.state.value.running)
            assertTrue(server.state.value.note!!.contains("wrong PINs"))
        } finally {
            server.stop()
        }
    }
}

package dev.nitrate.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedCheckTest {
    private fun step(id: String, vararg ms: Long, mbps: Double? = null) = SpeedStep(id, id, "", ms.toList(), mbps = mbps)

    @Test
    fun medianIgnoresOneHiccup() {
        val s = step(SpeedCheck.PING, 20, 900, 25)
        assertEquals(25, s.medianMs)
        assertEquals(Rating.GOOD, s.rating)
        assertEquals("25 ms (20–900)", s.result)
    }

    @Test
    fun allWellSaysSo() {
        val f = SpeedCheck.findings(listOf(step(SpeedCheck.PING, 20, 22, 25), step(SpeedCheck.SEARCH, 200, 210, 230), step(SpeedCheck.DOWNLOAD, 100, mbps = 85.0)))
        assertEquals(listOf("Everything is within normal speeds from this device."), f)
    }

    @Test
    fun pointsAtTheNetworkTheServerAndTheDevice() {
        val f = SpeedCheck.findings(
            listOf(
                step(SpeedCheck.PING, 240, 250, 260),
                step(SpeedCheck.SEARCH, 1900, 2000, 2100),
                step(SpeedCheck.PARSE, 400, 420, 410),
                step(SpeedCheck.DOWNLOAD, 100, mbps = 4.2),
            ),
        )
        assertTrue(f[0].startsWith("The network is slow: every request spends about 250 ms"))
        assertTrue(f[1].startsWith("The server is slow at searching the library: about 1750 ms"))
        assertTrue(f[2].startsWith("This device is slow at reading the server's answers: 410 ms"))
        assertTrue(f[3].startsWith("Download speed is about 4.2 Mbps"))
    }

    @Test
    fun failuresAreReported() {
        val s = SpeedStep(SpeedCheck.HOME, "Home page", "", error = "timeout")
        assertEquals(Rating.FAILED, s.rating)
        assertEquals("Home page failed: timeout", SpeedCheck.findings(listOf(s)).first())
    }
}

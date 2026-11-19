package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioDecodeReasonTest {
    @Test fun processingComesFirst() {
        val why = AudioPath.decodeReason("eac3", listOf("dialogue boost"), 1f, outputAccepts = true)!!
        assertTrue(why.startsWith("Dialogue boost is on"), why)
        assertTrue("Dolby Digital Plus" in why, why)
    }

    @Test fun speed() = assertTrue(AudioPath.decodeReason("ac3", emptyList(), 1.5f, true)!!.startsWith("Playing at 1.5×"))

    @Test fun outputRefuses() = assertTrue("won't take Dolby Digital Plus untouched" in AudioPath.decodeReason("eac3", emptyList(), 1f, outputAccepts = false)!!)

    @Test fun outputTakesItButDecoded() = assertTrue("takes Dolby Digital Plus untouched" in AudioPath.decodeReason("EAC3", emptyList(), 1f, true)!!)

    @Test fun formatsAlwaysDecodedSayNothing() {
        assertNull(AudioPath.decodeReason("aac", emptyList(), 1f, false))
        assertNull(AudioPath.decodeReason(null, emptyList(), 1f, false))
        assertNull(AudioPath.decodeReason("eac3", emptyList(), 1f, null))
    }

    @Test fun rowShowsOnlyWhenDecoded() {
        val path = AudioPath(inCodec = "eac3", delivery = AudioDelivery.DECODED, decodedWhy = "because")
        assertEquals("because", path.rows().toMap()["Why decoded"])
        assertNull(path.copy(delivery = AudioDelivery.BITSTREAM).rows().toMap()["Why decoded"])
    }
}

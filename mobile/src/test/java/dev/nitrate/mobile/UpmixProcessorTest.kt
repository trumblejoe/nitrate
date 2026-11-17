package dev.nitrate.mobile

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.nitrate.android.playback.UpmixAudioProcessor
import dev.nitrate.core.playback.SpeakerLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UpmixProcessorTest {
    private fun stereo16(frames: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i ->
            val s = (sin(2 * PI * 1000 * i / 48_000) * 12_000).toInt().toShort()
            b.putShort(s); b.putShort(s)
        }
        b.flip()
        return b
    }

    @Test fun stereoBecomesFivePointOne() {
        val p = UpmixAudioProcessor { SpeakerLayout.FIVE_ONE }
        val out = p.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(6, out.channelCount)
        assertEquals(48_000, out.sampleRate)
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(stereo16(4_800))
        val o = p.output.order(ByteOrder.nativeOrder())
        assertEquals(4_800 * 6 * 2, o.remaining())
        // Centred tone: the centre channel (2) carries it, the surrounds (4, 5) don't.
        var centre = 0; var surround = 0
        repeat(4_800) { i ->
            val frame = IntArray(6) { o.short.toInt() }
            if (i > 2_400) { centre = maxOf(centre, abs(frame[2])); surround = maxOf(surround, abs(frame[4]), abs(frame[5])) }
        }
        assertTrue("centre $centre", centre > 6_000)
        assertTrue("surround $surround", surround < 300)
        assertEquals(SpeakerLayout.FIVE_ONE, p.active)
    }

    @Test fun offOrNotStereoPassesThrough() {
        val off = UpmixAudioProcessor { null }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, off.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)))
        assertFalse(off.isActive)
        val surround = UpmixAudioProcessor { SpeakerLayout.FIVE_ONE }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, surround.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)))
        val twoOne = UpmixAudioProcessor { SpeakerLayout.STEREO_SUB }
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, twoOne.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)))
    }
}

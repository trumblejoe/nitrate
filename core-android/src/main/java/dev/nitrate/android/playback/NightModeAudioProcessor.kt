package dev.nitrate.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Night mode: lifts the centre (dialogue) channel of surround tracks, then evens out loud and
 * quiet passages with a compressor and makeup gain, and a limiter so peaks never clip. Works on
 * decoded PCM only, so it has no effect when audio is passed through to a receiver.
 */
@OptIn(UnstableApi::class)
class NightModeAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    private var channels = 2
    private var float = false
    private var envelope = 0f
    private var attack = 0f
    private var release = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8_000).toFloat()
        attack = exp(-1f / (ATTACK_S * rate))
        release = exp(-1f / (RELEASE_S * rate))
        envelope = 0f
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frame = FloatArray(channels)
        val bytesPerSample = if (float) 4 else 2
        val frames = size / (bytesPerSample * channels)
        repeat(frames) {
            for (c in 0 until channels) frame[c] = if (float) input.float else input.short / 32768f
            // 5.1 and 7.1 put the centre (dialogue) channel third.
            if (channels >= 6) frame[CENTER] *= CENTER_BOOST
            val peak = frame.maxOf { abs(it) }
            val coefficient = if (peak > envelope) attack else release
            envelope = coefficient * envelope + (1 - coefficient) * peak
            val gain = if (envelope > THRESHOLD) (THRESHOLD / envelope).pow(1 - 1 / RATIO) else 1f
            for (c in 0 until channels) {
                val v = (frame[c] * gain * MAKEUP).coerceIn(-LIMIT, LIMIT)
                if (float) out.putFloat(v) else out.putShort((v * 32767f).toInt().toShort())
            }
        }
        // Anything not making a whole frame passes through untouched.
        while (input.hasRemaining()) out.put(input.get())
        out.flip()
    }

    override fun onFlush() {
        envelope = 0f
    }

    private companion object {
        const val CENTER = 2
        const val CENTER_BOOST = 1.6f
        const val THRESHOLD = 0.12f
        const val RATIO = 4f
        const val MAKEUP = 2.2f
        const val LIMIT = 0.97f
        const val ATTACK_S = 0.005f
        const val RELEASE_S = 0.25f
    }
}

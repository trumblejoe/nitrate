package dev.nitrate.core.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dialogue boost: lifts voices over music and effects. Surround tracks carry dialogue in the
 * centre channel, so that's raised and the rest lowered a little. Stereo has no voice channel:
 * the part common to both sides (where dialogue is mixed) is filtered to the speech band and
 * added back louder, and the difference between the sides (wide music, ambience) is turned down.
 * Works on one frame of samples at a time (-1..1, interleaved channel order), in place.
 */
class DialogueBoost(sampleRate: Int, private val channels: Int) {
    private val b0: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        // A band-pass around the middle of speech (RBJ cookbook, 0 dB peak).
        val w0 = 2 * PI * SPEECH_HZ / sampleRate.coerceAtLeast(8_000)
        val alpha = sin(w0) / (2 * SPEECH_Q)
        val a0 = 1 + alpha
        b0 = (alpha / a0).toFloat()
        b2 = (-alpha / a0).toFloat()
        a1 = (-2 * cos(w0) / a0).toFloat()
        a2 = ((1 - alpha) / a0).toFloat()
    }

    fun process(frame: FloatArray) {
        when {
            channels >= 6 -> {
                for (c in 0 until channels) frame[c] *= if (c == CENTER) CENTER_GAIN else if (c == LFE) LFE_GAIN else SURROUND_GAIN
            }
            channels == 2 -> {
                val mid = (frame[0] + frame[1]) / 2
                val side = (frame[0] - frame[1]) / 2
                val voiced = mid + speech(mid) * SPEECH_GAIN
                frame[0] = (voiced + side * SIDE_GAIN) * OUT_GAIN
                frame[1] = (voiced - side * SIDE_GAIN) * OUT_GAIN
            }
            channels == 1 -> frame[0] = (frame[0] + speech(frame[0]) * SPEECH_GAIN) * OUT_GAIN
        }
        for (c in 0 until channels) frame[c] = frame[c].coerceIn(-LIMIT, LIMIT)
    }

    private fun speech(x: Float): Float {
        val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    companion object {
        const val SPEECH_HZ = 1_800.0
        const val SPEECH_Q = 0.6
        const val SPEECH_GAIN = 1.0f
        const val SIDE_GAIN = 0.55f
        const val OUT_GAIN = 0.8f
        const val CENTER = 2
        const val LFE = 3
        const val CENTER_GAIN = 1.7f
        const val SURROUND_GAIN = 0.7f
        const val LFE_GAIN = 0.6f
        const val LIMIT = 0.98f
    }
}

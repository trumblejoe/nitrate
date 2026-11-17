package dev.nitrate.core.playback

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Stereo to 3.1, 5.1 or 7.1, in the manner of a Pro Logic II style passive-plus-steering upmix:
 * - centre: what left and right share, and only as far as they really match (dialogue locks to
 *   the centre speaker; wide music stays out of it);
 * - fronts: the originals, less part of that centre, so voices don't come from three places;
 * - surrounds: the difference between left and right (ambience, reverb, width), delayed ~15 ms,
 *   softened above ~7 kHz, left and right in opposite phase and a little apart in time, so it
 *   reads as space around you rather than an echo;
 * - LFE: the shared bass under ~120 Hz, at a modest level (the receiver manages the sub).
 * Overall loudness is kept about the same, with a limiter.
 *
 * Channel order is Android's: FL, FR, FC, LFE, BL, BR (and SL, SR for 7.1). 3.1 goes out as 5.1
 * with silent surrounds, since Android has no 3.1 layout of its own.
 */
class Upmixer(sampleRate: Int, val layout: SpeakerLayout) {
    val outChannels: Int = layout.upmixChannels ?: 2
    private val rate = sampleRate.coerceAtLeast(8_000).toFloat()

    // How much left and right match, smoothed over ~60 ms.
    private val smooth = exp(-1f / (0.06f * rate))
    private var pLL = 0f
    private var pRR = 0f
    private var pLR = 0f

    // Surround delays (samples) in a ring buffer.
    private val ring = FloatArray((0.05f * rate).toInt() + 2)
    private var at = 0
    private val delayLeft = (0.015f * rate).toInt()
    private val delayRight = (0.017f * rate).toInt()
    private val delayBack = (0.022f * rate).toInt()

    // One-pole filters: surrounds softened above ~7 kHz, LFE under ~120 Hz.
    private val surroundCoef = onePole(7_000f)
    private var surroundState = 0f
    private val lfeCoef = onePole(120f)
    private var lfeState = 0f

    private fun onePole(hz: Float) = exp(-2f * PI.toFloat() * hz / rate)

    /** Turns one stereo frame ([left], [right]) into [out] ([outChannels] samples). */
    fun process(left: Float, right: Float, out: FloatArray) {
        pLL = smooth * pLL + (1 - smooth) * left * left
        pRR = smooth * pRR + (1 - smooth) * right * right
        pLR = smooth * pLR + (1 - smooth) * left * right
        val match = if (pLL > 1e-9f && pRR > 1e-9f) (pLR / sqrt(pLL * pRR)).coerceIn(0f, 1f) else 0f

        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        val centre = mid * match

        surroundState = surroundCoef * surroundState + (1 - surroundCoef) * side
        ring[at] = surroundState
        fun delayed(n: Int) = ring[(at - n + ring.size) % ring.size]
        lfeState = lfeCoef * lfeState + (1 - lfeCoef) * mid

        val fl = left - centre * FRONT_TAKE
        val fr = right - centre * FRONT_TAKE
        val c = if (layout == SpeakerLayout.STEREO || layout == SpeakerLayout.STEREO_SUB) 0f else centre * CENTRE_GAIN
        val lfe = lfeState * LFE_GAIN
        out[0] = limit(fl * OUT_GAIN)
        out[1] = limit(fr * OUT_GAIN)
        if (outChannels >= 6) {
            out[2] = limit(c * OUT_GAIN)
            out[3] = limit(lfe * OUT_GAIN)
            val surround = layout == SpeakerLayout.FIVE_ONE || layout == SpeakerLayout.SEVEN_ONE
            val bl = if (surround) delayed(delayLeft) * SURROUND_GAIN else 0f
            val br = if (surround) -delayed(delayRight) * SURROUND_GAIN else 0f
            if (layout == SpeakerLayout.SEVEN_ONE) {
                // Back pair a little later and softer; the side pair in front of it.
                out[4] = limit(delayed(delayBack) * BACK_GAIN * OUT_GAIN)
                out[5] = limit(-delayed(delayBack + (delayRight - delayLeft)) * BACK_GAIN * OUT_GAIN)
                out[6] = limit(bl * OUT_GAIN)
                out[7] = limit(br * OUT_GAIN)
            } else {
                out[4] = limit(bl * OUT_GAIN)
                out[5] = limit(br * OUT_GAIN)
            }
        }
        at = (at + 1) % ring.size
    }

    fun reset() {
        pLL = 0f; pRR = 0f; pLR = 0f
        ring.fill(0f)
        surroundState = 0f
        lfeState = 0f
    }

    private fun limit(v: Float) = v.coerceIn(-LIMIT, LIMIT)

    companion object {
        const val FRONT_TAKE = 0.5f
        const val CENTRE_GAIN = 0.85f
        const val SURROUND_GAIN = 0.7f
        const val BACK_GAIN = 0.6f
        const val LFE_GAIN = 0.5f
        /** Keeps the whole about as loud as the stereo was, with headroom. */
        const val OUT_GAIN = 0.9f
        const val LIMIT = 0.98f
    }
}

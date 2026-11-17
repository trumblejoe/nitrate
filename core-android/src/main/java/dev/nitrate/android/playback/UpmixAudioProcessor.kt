package dev.nitrate.android.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.nitrate.core.playback.SpeakerLayout
import dev.nitrate.core.playback.Upmixer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stereo spread over the speakers (core [Upmixer]): active only when [decide] gives a layout for
 * this stereo PCM; otherwise it passes the sound through untouched.
 */
@OptIn(UnstableApi::class)
class UpmixAudioProcessor(private val decide: () -> SpeakerLayout?) : BaseAudioProcessor() {
    private var upmixer: Upmixer? = null
    private var float = false

    /** The layout in use since the sound was last set up (null: not upmixing). */
    @Volatile var active: SpeakerLayout? = null
        private set

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        active = null
        val pcm = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val layout = if (pcm && inputAudioFormat.channelCount == 2) decide() else null
        val channels = layout?.upmixChannels
        if (layout == null || channels == null) {
            upmixer = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        upmixer = Upmixer(inputAudioFormat.sampleRate, layout)
        active = layout
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, channels, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val up = upmixer ?: return
        val bytes = if (float) 4 else 2
        val frames = inputBuffer.remaining() / (bytes * 2)
        if (frames == 0) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(frames * up.outChannels * bytes)
        val frame = FloatArray(up.outChannels)
        repeat(frames) {
            val l = if (float) input.float else input.short / 32768f
            val r = if (float) input.float else input.short / 32768f
            up.process(l, r, frame)
            for (v in frame) if (float) out.putFloat(v) else out.putShort((v * 32767f).toInt().toShort())
        }
        // A partial frame (never expected) is dropped.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        upmixer?.reset()
    }

    override fun onReset() {
        upmixer = null
        active = null
    }
}

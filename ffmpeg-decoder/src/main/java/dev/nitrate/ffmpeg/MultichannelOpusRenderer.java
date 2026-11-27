package dev.nitrate.ffmpeg;

import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;

/**
 * FFmpeg's Opus decoder for Opus with more than two channels, placed ahead of the device's own
 * decoders. Some devices' built-in Opus decoder (Android's, in a separate process) hands 5.1 and 7.1
 * over in Opus's own channel order, so the centre (the dialogue) comes out of the left front, and on
 * slower devices it falls behind; FFmpeg's puts the channels in the order the device plays them and
 * decodes in this process. Anything else (stereo Opus, other formats) isn't taken here: the device's
 * decoders come first as before. Without an FFmpeg build that has Opus it declines too.
 */
@UnstableApi
public final class MultichannelOpusRenderer extends FfmpegAudioRenderer {

  public MultichannelOpusRenderer(
      @Nullable Handler eventHandler,
      @Nullable AudioRendererEventListener eventListener,
      AudioSink audioSink) {
    super(eventHandler, eventListener, audioSink);
  }

  /** Whether [format] is Opus with more than two channels (what this renderer takes). */
  public static boolean takes(Format format) {
    return MimeTypes.AUDIO_OPUS.equals(format.sampleMimeType) && format.channelCount > 2;
  }

  @Override
  protected @C.FormatSupport int supportsFormatInternal(Format format) {
    if (!takes(format)) return C.FORMAT_UNSUPPORTED_TYPE;
    return super.supportsFormatInternal(format);
  }

  @Override
  public String getName() {
    return "MultichannelOpusRenderer";
  }
}

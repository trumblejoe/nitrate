package dev.nitrate.ffmpeg;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.Objects;

/**
 * Plays video Nitrate decodes in software ({@link FfmpegVideoDecoder}). Added after the device's
 * own (MediaCodec) renderer, so it's only used when the device can't decode the video itself.
 * Software decoding keeps to 1080p; larger pictures are reported as beyond its means.
 */
@UnstableApi
public final class FfmpegVideoRenderer extends DecoderVideoRenderer {
  private static final int MAX_WIDTH = 1920;
  private static final int MAX_HEIGHT = 1088;

  @Nullable private FfmpegVideoDecoder decoder;

  public FfmpegVideoRenderer(long allowedJoiningTimeMs, @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
  }

  @Override
  public String getName() {
    return "NitrateFfmpegVideoRenderer";
  }

  @Override
  public @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    if (!FfmpegVideoDecoder.supports(format.sampleMimeType)) {
      return RendererCapabilities.create(FfmpegVideoDecoder.codecName(format.sampleMimeType) == null
          ? C.FORMAT_UNSUPPORTED_TYPE : C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    boolean fits = (format.width == Format.NO_VALUE || format.width <= MAX_WIDTH)
        && (format.height == Format.NO_VALUE || format.height <= MAX_HEIGHT);
    return RendererCapabilities.create(
        fits ? C.FORMAT_HANDLED : C.FORMAT_EXCEEDS_CAPABILITIES,
        ADAPTIVE_SEAMLESS,
        TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegVideoDecoder.Error {
    int cores = Runtime.getRuntime().availableProcessors();
    FfmpegVideoDecoder created = new FfmpegVideoDecoder(format, Math.max(1, Math.min(cores, 8)));
    decoder = created;
    return created;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegVideoDecoder.Error {
    try {
      FfmpegVideoDecoder.renderToSurface(outputBuffer, surface);
    } finally {
      outputBuffer.release();
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) decoder.setOutputMode(outputMode);
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(String decoderName, Format oldFormat, Format newFormat) {
    boolean same = Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType)
        && oldFormat.initializationDataEquals(newFormat);
    return new DecoderReuseEvaluation(decoderName, oldFormat, newFormat,
        same ? DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : DecoderReuseEvaluation.REUSE_RESULT_NO,
        same ? 0 : DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED);
  }
}

package dev.nitrate.ffmpeg;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Nitrate's software video decoder: FFmpeg's H.264 (including 10-bit), MPEG-2, MPEG-4 Part 2 and
 * VC-1 decoders, for devices with no decoder of their own. Frames come out as 8-bit 4:2:0 and are
 * drawn into the Surface as YV12 by {@link #renderToSurface}.
 */
@UnstableApi
public final class FfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegVideoDecoder.Error> {

  /** A decoding failure. */
  public static final class Error extends DecoderException {
    public Error(String message) {
      super(message);
    }

    public Error(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final int BUFFERS = 8;

  private final String codec;
  private long handle;
  private volatile @C.VideoOutputMode int outputMode = C.VIDEO_OUTPUT_MODE_NONE;
  private final int[] info = new int[3];
  private final long[] time = new long[1];

  public FfmpegVideoDecoder(Format format, int threads) throws Error {
    super(new DecoderInputBuffer[BUFFERS], new VideoDecoderOutputBuffer[BUFFERS]);
    String name = codecName(format.sampleMimeType);
    if (name == null || !FfmpegLibrary.isAvailable()) throw new Error("No software decoder for " + format.sampleMimeType);
    codec = name;
    handle = nativeInit(name, extraData(format.initializationData), threads);
    if (handle == 0) throw new Error("Couldn't open the " + name + " decoder");
    int size = format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : 1024 * 1024;
    setInitialInputBufferSize(size);
  }

  /** FFmpeg's decoder for a MIME type, or null when Nitrate doesn't decode it in software. */
  @Nullable
  public static String codecName(@Nullable String mime) {
    if (mime == null) return null;
    switch (mime) {
      case MimeTypes.VIDEO_H264:
        return "h264";
      case MimeTypes.VIDEO_MPEG2:
        return "mpeg2video";
      case MimeTypes.VIDEO_MP4V:
        return "mpeg4";
      case MimeTypes.VIDEO_VC1:
        return "vc1";
      default:
        return null;
    }
  }

  /** Whether this build decodes [mime] in software. */
  public static boolean supports(@Nullable String mime) {
    String name = codecName(mime);
    if (name == null || !FfmpegLibrary.isAvailable()) return false;
    try {
      return nativeHasDecoder(name);
    } catch (UnsatisfiedLinkError e) {
      // A library built before video decoding was added.
      return false;
    }
  }

  /** Codec setup data (SPS/PPS, sequence headers) joined, as the extractor gives it. */
  @Nullable
  private static byte[] extraData(List<byte[]> data) {
    if (data.isEmpty()) return null;
    int size = 0;
    for (byte[] d : data) size += d.length;
    byte[] out = new byte[size];
    int at = 0;
    for (byte[] d : data) {
      System.arraycopy(d, 0, out, at, d.length);
      at += d.length;
    }
    return out;
  }

  @Override
  public String getName() {
    return "nitrate.ffmpeg." + codec;
  }

  public void setOutputMode(@C.VideoOutputMode int mode) {
    outputMode = mode;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected Error createUnexpectedDecodeException(Throwable error) {
    return new Error("Unexpected decode error", error);
  }

  @Nullable
  @Override
  protected Error decode(DecoderInputBuffer input, VideoDecoderOutputBuffer output, boolean reset) {
    if (reset) nativeFlush(handle);
    ByteBuffer data = input.data;
    if (data != null && data.limit() > 0) {
      int result = nativeSend(handle, data, data.limit(), input.timeUs);
      if (result < 0) return new Error("Decoding failed (" + result + ")");
    }
    // Frames come out in display order, some packets later (B-frames, threads): one per call.
    if (nativeReceive(handle, info, time) == 0) {
      output.init(input.timeUs, outputMode, null);
      output.shouldBeSkipped = true;
      return null;
    }
    long timeUs = time[0];
    output.init(timeUs, outputMode, null);
    if (!isAtLeastOutputStartTimeUs(timeUs)) {
      output.shouldBeSkipped = true;
      return null;
    }
    int width = info[0];
    int height = info[1];
    int yStride = (width + 15) & ~15;
    int uvStride = (((width + 1) / 2) + 15) & ~15;
    if (outputMode == C.VIDEO_OUTPUT_MODE_YUV || outputMode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      if (!output.initForYuvFrame(width, height, yStride, uvStride, info[2])) {
        return new Error("Frame too large: " + width + "x" + height);
      }
      if (!nativeCopy(handle, output.data, yStride, uvStride)) return new Error("Couldn't convert the frame");
    } else {
      // No output yet: nothing to draw, just keep time.
      output.width = width;
      output.height = height;
    }
    return null;
  }

  /** Draws a decoded frame into [surface]. */
  public static void renderToSurface(VideoDecoderOutputBuffer frame, Surface surface) throws Error {
    if (frame.data == null || frame.yuvStrides == null) return;
    if (!nativeRender(surface, frame.data, frame.width, frame.height, frame.yuvStrides[0], frame.yuvStrides[1], frame.colorspace)) {
      throw new Error("Couldn't draw the frame");
    }
  }

  @Override
  public void release() {
    super.release();
    if (handle != 0) {
      nativeRelease(handle);
      handle = 0;
    }
  }

  private static native boolean nativeHasDecoder(String name);

  private static native long nativeInit(String name, @Nullable byte[] extraData, int threads);

  private static native int nativeSend(long handle, ByteBuffer data, int size, long timeUs);

  private static native int nativeReceive(long handle, int[] info, long[] time);

  private static native boolean nativeCopy(long handle, ByteBuffer out, int yStride, int uvStride);

  private static native void nativeFlush(long handle);

  private static native void nativeRelease(long handle);

  private static native boolean nativeRender(
      Surface surface, ByteBuffer data, int width, int height, int yStride, int uvStride, int colorspace);
}

// Nitrate's software video decoder over FFmpeg (LGPL build, see tools/build-ffmpeg.sh): H.264
// (including 10-bit "Hi10P"), MPEG-2, MPEG-4 Part 2 and VC-1, for devices with no decoder of
// their own. Packets in, frames out as 8-bit 4:2:0 planes into the output buffer, then copied
// into the Surface as YV12. Nitrate's own code (not part of Media3's extension).
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>

#include <deque>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "NitrateFfmpegVideo"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define VIDEO_FUNC(RETURN, NAME, ...) \
    extern "C" JNIEXPORT RETURN JNICALL Java_dev_nitrate_ffmpeg_FfmpegVideoDecoder_##NAME(JNIEnv *env, jclass, ##__VA_ARGS__)

namespace {

// HAL_PIXEL_FORMAT_YV12: Y, then V, then U; strides aligned to 16.
const int kYv12 = 0x32315659;

// Colour spaces the output buffer reports (Media3's VideoDecoderOutputBuffer.COLORSPACE_*).
const int kColorspaceUnknown = 0, kColorspaceBt601 = 1, kColorspaceBt709 = 2, kColorspaceBt2020 = 3;

struct Decoder {
    AVCodecContext *context = nullptr;
    AVPacket *packet = nullptr;
    std::deque<AVFrame *> frames;
    SwsContext *sws = nullptr;
    // Frame being handed over: received, waiting for copyFrame.
    AVFrame *current = nullptr;
};

void clearFrames(Decoder *d) {
    for (AVFrame *f : d->frames) av_frame_free(&f);
    d->frames.clear();
    if (d->current) av_frame_free(&d->current);
}

int colorspaceOf(const AVFrame *f) {
    switch (f->colorspace) {
        case AVCOL_SPC_BT709: return kColorspaceBt709;
        case AVCOL_SPC_BT470BG:
        case AVCOL_SPC_SMPTE170M: return kColorspaceBt601;
        case AVCOL_SPC_BT2020_NCL:
        case AVCOL_SPC_BT2020_CL: return kColorspaceBt2020;
        default: return f->height > 576 ? kColorspaceBt709 : kColorspaceBt601;
    }
}

// Receives every frame the decoder has ready into the queue.
int drain(Decoder *d) {
    for (;;) {
        AVFrame *frame = av_frame_alloc();
        if (!frame) return AVERROR(ENOMEM);
        int r = avcodec_receive_frame(d->context, frame);
        if (r < 0) {
            av_frame_free(&frame);
            return r == AVERROR(EAGAIN) || r == AVERROR_EOF ? 0 : r;
        }
        d->frames.push_back(frame);
    }
}

// Android's ANativeWindow_setBuffersDataSpace (API 28), looked up so older devices still load.
typedef int32_t (*SetDataSpace)(ANativeWindow *, int32_t);
SetDataSpace setDataSpace() {
    static SetDataSpace fn = reinterpret_cast<SetDataSpace>(dlsym(RTLD_DEFAULT, "ANativeWindow_setBuffersDataSpace"));
    return fn;
}

}  // namespace

VIDEO_FUNC(jboolean, nativeHasDecoder, jstring name) {
    const char *n = env->GetStringUTFChars(name, nullptr);
    bool found = avcodec_find_decoder_by_name(n) != nullptr;
    env->ReleaseStringUTFChars(name, n);
    return found;
}

VIDEO_FUNC(jlong, nativeInit, jstring name, jbyteArray extraData, jint threads) {
    const char *n = env->GetStringUTFChars(name, nullptr);
    const AVCodec *codec = avcodec_find_decoder_by_name(n);
    env->ReleaseStringUTFChars(name, n);
    if (!codec) return 0;
    Decoder *d = new Decoder();
    d->context = avcodec_alloc_context3(codec);
    d->packet = av_packet_alloc();
    if (!d->context || !d->packet) { avcodec_free_context(&d->context); av_packet_free(&d->packet); delete d; return 0; }
    if (extraData) {
        jsize size = env->GetArrayLength(extraData);
        d->context->extradata = static_cast<uint8_t *>(av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE));
        if (d->context->extradata) {
            env->GetByteArrayRegion(extraData, 0, size, reinterpret_cast<jbyte *>(d->context->extradata));
            d->context->extradata_size = size;
        }
    }
    // Timestamps go in and come out in microseconds.
    d->context->pkt_timebase = AVRational{1, 1000000};
    d->context->thread_count = threads;
    d->context->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    d->context->err_recognition = 0;
    if (avcodec_open2(d->context, codec, nullptr) < 0) {
        LOGE("Couldn't open the %s decoder", codec->name);
        avcodec_free_context(&d->context);
        av_packet_free(&d->packet);
        delete d;
        return 0;
    }
    return reinterpret_cast<jlong>(d);
}

// 0: sent, < 0: error.
VIDEO_FUNC(jint, nativeSend, jlong handle, jobject data, jint size, jlong timeUs) {
    Decoder *d = reinterpret_cast<Decoder *>(handle);
    uint8_t *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(data));
    av_packet_unref(d->packet);
    if (av_new_packet(d->packet, size) < 0) return AVERROR(ENOMEM);
    memcpy(d->packet->data, bytes, size);
    d->packet->pts = timeUs;
    d->packet->dts = AV_NOPTS_VALUE;
    int r = avcodec_send_packet(d->context, d->packet);
    if (r == AVERROR(EAGAIN)) {
        // Frames waiting: take them, then the packet fits.
        r = drain(d);
        if (r < 0) return r;
        r = avcodec_send_packet(d->context, d->packet);
    }
    // A damaged packet is skipped, as hardware decoders do; only lasting failures stop playback.
    if (r == AVERROR_INVALIDDATA) return 0;
    if (r < 0) return r;
    return drain(d);
}

// 1: a frame is ready (size and timing in [info]: width, height, colorspace; [time]: µs),
// 0: none yet.
VIDEO_FUNC(jint, nativeReceive, jlong handle, jintArray info, jlongArray time) {
    Decoder *d = reinterpret_cast<Decoder *>(handle);
    if (d->current) av_frame_free(&d->current);
    if (d->frames.empty()) return 0;
    d->current = d->frames.front();
    d->frames.pop_front();
    AVFrame *f = d->current;
    jint values[3] = {f->width, f->height, colorspaceOf(f)};
    env->SetIntArrayRegion(info, 0, 3, values);
    jlong pts = f->best_effort_timestamp != AV_NOPTS_VALUE ? f->best_effort_timestamp : f->pts;
    env->SetLongArrayRegion(time, 0, 1, &pts);
    return 1;
}

// Copies the received frame into [out] as 8-bit 4:2:0 planes (Y, U, V) with the given strides.
VIDEO_FUNC(jboolean, nativeCopy, jlong handle, jobject out, jint yStride, jint uvStride) {
    Decoder *d = reinterpret_cast<Decoder *>(handle);
    AVFrame *f = d->current;
    if (!f) return JNI_FALSE;
    uint8_t *dst = static_cast<uint8_t *>(env->GetDirectBufferAddress(out));
    if (!dst) return JNI_FALSE;
    int w = f->width, h = f->height, uvh = (h + 1) / 2;
    uint8_t *planes[3] = {dst, dst + yStride * h, dst + yStride * h + uvStride * uvh};
    int strides[3] = {yStride, uvStride, uvStride};
    if (f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P) {
        for (int y = 0; y < h; y++) memcpy(planes[0] + y * yStride, f->data[0] + y * f->linesize[0], w);
        for (int p = 1; p < 3; p++)
            for (int y = 0; y < uvh; y++) memcpy(planes[p] + y * uvStride, f->data[p] + y * f->linesize[p], (w + 1) / 2);
        return JNI_TRUE;
    }
    // 10-bit, 4:2:2, 4:4:4...: converted (the picture keeps its size).
    d->sws = sws_getCachedContext(d->sws, w, h, static_cast<AVPixelFormat>(f->format), w, h, AV_PIX_FMT_YUV420P,
                                  SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!d->sws) return JNI_FALSE;
    sws_scale(d->sws, f->data, f->linesize, 0, h, planes, strides);
    return JNI_TRUE;
}

VIDEO_FUNC(void, nativeFlush, jlong handle) {
    Decoder *d = reinterpret_cast<Decoder *>(handle);
    clearFrames(d);
    avcodec_flush_buffers(d->context);
}

VIDEO_FUNC(void, nativeRelease, jlong handle) {
    Decoder *d = reinterpret_cast<Decoder *>(handle);
    if (!d) return;
    clearFrames(d);
    sws_freeContext(d->sws);
    avcodec_free_context(&d->context);
    av_packet_free(&d->packet);
    delete d;
}

// Draws 8-bit 4:2:0 planes ([data]: Y, U, V) into [surface] as YV12.
VIDEO_FUNC(jboolean, nativeRender, jobject surface, jobject data, jint width, jint height, jint yStride, jint uvStride, jint colorspace) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    uint8_t *src = static_cast<uint8_t *>(env->GetDirectBufferAddress(data));
    bool ok = false;
    if (src && ANativeWindow_setBuffersGeometry(window, width, height, kYv12) == 0) {
        if (SetDataSpace set = setDataSpace()) {
            // ADATASPACE_BT709 / BT601_625 / BT2020 (limited range).
            int32_t space = colorspace == kColorspaceBt2020 ? 0x10C60000 : colorspace == kColorspaceBt601 ? 0x10C20000 : 0x10C10000;
            set(window, space);
        }
        ANativeWindow_Buffer buffer;
        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
            int h = buffer.height < height ? buffer.height : height;
            int w = buffer.width < width ? buffer.width : width;
            int uvh = (h + 1) / 2, uvw = (w + 1) / 2;
            uint8_t *dst = static_cast<uint8_t *>(buffer.bits);
            int dstY = buffer.stride;
            int dstUv = ((dstY / 2) + 15) & ~15;
            const uint8_t *srcY = src, *srcU = src + yStride * height, *srcV = srcU + uvStride * ((height + 1) / 2);
            uint8_t *dstV = dst + dstY * buffer.height;
            uint8_t *dstU = dstV + dstUv * ((buffer.height + 1) / 2);
            for (int y = 0; y < h; y++) memcpy(dst + y * dstY, srcY + y * yStride, w);
            for (int y = 0; y < uvh; y++) {
                memcpy(dstU + y * dstUv, srcU + y * uvStride, uvw);
                memcpy(dstV + y * dstUv, srcV + y * uvStride, uvw);
            }
            ANativeWindow_unlockAndPost(window);
            ok = true;
        }
    }
    ANativeWindow_release(window);
    return ok;
}

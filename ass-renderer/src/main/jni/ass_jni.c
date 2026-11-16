// JNI bridge to libass for Nitrate's subtitle layer: loads one ASS/SSA track with its fonts and
// renders a moment of it into an Android bitmap (premultiplied RGBA), only when it changed.
#include <jni.h>
#include <android/bitmap.h>
#include <stdlib.h>
#include <string.h>
#include <ass/ass.h>

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int fonts_set;
} Ass;

static void quiet(int level, const char *fmt, va_list args, void *data) { (void)level; (void)fmt; (void)args; (void)data; }

JNIEXPORT jlong JNICALL Java_dev_nitrate_ass_AssRenderer_nativeCreate(JNIEnv *env, jclass cls) {
    Ass *a = calloc(1, sizeof(Ass));
    if (!a) return 0;
    a->library = ass_library_init();
    if (!a->library) { free(a); return 0; }
    ass_set_message_cb(a->library, quiet, NULL);
    ass_set_extract_fonts(a->library, 1);
    a->renderer = ass_renderer_init(a->library);
    if (!a->renderer) { ass_library_done(a->library); free(a); return 0; }
    return (jlong)(intptr_t)a;
}

JNIEXPORT void JNICALL Java_dev_nitrate_ass_AssRenderer_nativeAddFont(JNIEnv *env, jclass cls, jlong h, jstring name, jbyteArray data) {
    Ass *a = (Ass *)(intptr_t)h;
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    ass_add_font(a->library, n, (const char *)bytes, len);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, n);
}

JNIEXPORT void JNICALL Java_dev_nitrate_ass_AssRenderer_nativeSetFonts(JNIEnv *env, jclass cls, jlong h, jstring path, jstring family) {
    Ass *a = (Ass *)(intptr_t)h;
    const char *p = path ? (*env)->GetStringUTFChars(env, path, NULL) : NULL;
    const char *f = family ? (*env)->GetStringUTFChars(env, family, NULL) : NULL;
    // No system font lookup on Android: the file's own fonts, then this default.
    ass_set_fonts(a->renderer, p, f, ASS_FONTPROVIDER_NONE, NULL, 0);
    a->fonts_set = 1;
    if (p) (*env)->ReleaseStringUTFChars(env, path, p);
    if (f) (*env)->ReleaseStringUTFChars(env, family, f);
}

JNIEXPORT jboolean JNICALL Java_dev_nitrate_ass_AssRenderer_nativeLoadTrack(JNIEnv *env, jclass cls, jlong h, jbyteArray data) {
    Ass *a = (Ass *)(intptr_t)h;
    if (a->track) { ass_free_track(a->track); a->track = NULL; }
    jsize len = (*env)->GetArrayLength(env, data);
    char *copy = malloc(len + 1);
    if (!copy) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)copy);
    copy[len] = 0;
    a->track = ass_read_memory(a->library, copy, len, NULL);
    free(copy);
    return a->track != NULL;
}

JNIEXPORT void JNICALL Java_dev_nitrate_ass_AssRenderer_nativeSetSize(JNIEnv *env, jclass cls, jlong h, jint frameW, jint frameH, jint storageW, jint storageH) {
    Ass *a = (Ass *)(intptr_t)h;
    ass_set_frame_size(a->renderer, frameW, frameH);
    if (storageW > 0 && storageH > 0) ass_set_storage_size(a->renderer, storageW, storageH);
}

// Over the bitmap (premultiplied RGBA bytes): each libass image is a coverage mask in one colour,
// colour as 0xRRGGBBTT with TT transparency.
static void blend(uint8_t *pixels, int stride, int width, int height, const ASS_Image *img) {
    for (; img; img = img->next) {
        if (img->w <= 0 || img->h <= 0) continue;
        uint32_t c = img->color;
        int r = (c >> 24) & 0xFF, g = (c >> 16) & 0xFF, b = (c >> 8) & 0xFF, opacity = 255 - (c & 0xFF);
        for (int y = 0; y < img->h; y++) {
            int dy = img->dst_y + y;
            if (dy < 0 || dy >= height) continue;
            const uint8_t *src = img->bitmap + y * img->stride;
            uint8_t *row = pixels + dy * stride;
            for (int x = 0; x < img->w; x++) {
                int dx = img->dst_x + x;
                if (dx < 0 || dx >= width) continue;
                int k = src[x] * opacity / 255;
                if (k == 0) continue;
                uint8_t *p = row + dx * 4;
                int inv = 255 - k;
                p[0] = (uint8_t)((r * k + p[0] * inv) / 255);
                p[1] = (uint8_t)((g * k + p[1] * inv) / 255);
                p[2] = (uint8_t)((b * k + p[2] * inv) / 255);
                p[3] = (uint8_t)(k + p[3] * inv / 255);
            }
        }
    }
}

// 0: unchanged since last time, 1: drawn, 2: nothing to show.
JNIEXPORT jint JNICALL Java_dev_nitrate_ass_AssRenderer_nativeRender(JNIEnv *env, jclass cls, jlong h, jlong timeMs, jobject bitmap, jboolean force) {
    Ass *a = (Ass *)(intptr_t)h;
    if (!a->track) return 2;
    if (!a->fonts_set) { ass_set_fonts(a->renderer, NULL, NULL, ASS_FONTPROVIDER_NONE, NULL, 0); a->fonts_set = 1; }
    int changed = 0;
    ASS_Image *img = ass_render_frame(a->renderer, a->track, (long long)timeMs, &changed);
    if (!changed && !force) return 0;
    // Nothing to show: the caller hides the layer, so the bitmap needn't be touched.
    if (!img) return 2;
    AndroidBitmapInfo info;
    void *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 2;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return 2;
    for (uint32_t y = 0; y < info.height; y++) memset((uint8_t *)pixels + y * info.stride, 0, info.width * 4);
    blend(pixels, info.stride, info.width, info.height, img);
    AndroidBitmap_unlockPixels(env, bitmap);
    return 1;
}

JNIEXPORT void JNICALL Java_dev_nitrate_ass_AssRenderer_nativeRelease(JNIEnv *env, jclass cls, jlong h) {
    Ass *a = (Ass *)(intptr_t)h;
    if (!a) return;
    if (a->track) ass_free_track(a->track);
    ass_renderer_done(a->renderer);
    ass_library_done(a->library);
    free(a);
}

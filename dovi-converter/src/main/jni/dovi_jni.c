// JNI bridge to libdovi for Nitrate: rewrites one Dolby Vision RPU NAL unit (HEVC type 62, as it
// sits in a dual-layer profile 7 stream) as a profile 8.1 RPU NAL unit. Nitrate's own code.
#include <jni.h>
#include <stddef.h>
#include <stdint.h>

// libdovi's C API (dolby_vision crate, feature "capi"); only what this bridge uses.
typedef struct RpuOpaque RpuOpaque;
typedef struct { const uint8_t *data; size_t len; } DoviData;
RpuOpaque *dovi_parse_unspec62_nalu(const uint8_t *buf, size_t len);
const char *dovi_rpu_get_error(const RpuOpaque *ptr);
int32_t dovi_convert_rpu_with_mode(RpuOpaque *ptr, uint8_t mode);
const DoviData *dovi_write_unspec62_nalu(RpuOpaque *ptr);
void dovi_data_free(const DoviData *data);
void dovi_rpu_free(RpuOpaque *ptr);

// Mode 2: profile 8.1 compatible (handles profiles 5, 7 and 8); the mapping curves become no-ops,
// which is right once the enhancement layer is gone.
#define TO_PROFILE_81 2

// [nal]: the NAL unit without its start code (from the 0x7C01 header). Returns the rewritten NAL
// unit (with header), or null when it can't be read or converted.
JNIEXPORT jbyteArray JNICALL Java_dev_nitrate_dovi_RpuConverter_nativeToProfile81(JNIEnv *env, jclass cls, jbyteArray nal) {
    jsize len = (*env)->GetArrayLength(env, nal);
    jbyte *bytes = (*env)->GetByteArrayElements(env, nal, NULL);
    if (!bytes) return NULL;
    RpuOpaque *rpu = dovi_parse_unspec62_nalu((const uint8_t *)bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, nal, bytes, JNI_ABORT);
    if (!rpu) return NULL;
    jbyteArray out = NULL;
    if (!dovi_rpu_get_error(rpu) && dovi_convert_rpu_with_mode(rpu, TO_PROFILE_81) == 0) {
        const DoviData *data = dovi_write_unspec62_nalu(rpu);
        if (data && !dovi_rpu_get_error(rpu) && data->len > 0) {
            out = (*env)->NewByteArray(env, (jsize)data->len);
            if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)data->len, (const jbyte *)data->data);
        }
        if (data) dovi_data_free(data);
    }
    dovi_rpu_free(rpu);
    return out;
}

#!/usr/bin/env bash
# Builds FFmpeg for Nitrate's audio decoder (ffmpeg-decoder): LGPL 2.1+ only (no --enable-gpl,
# no non-free parts), audio decoders only, static libraries for armeabi-v7a and arm64-v8a, placed
# where ffmpeg-decoder/src/main/jni/CMakeLists.txt links them into libffmpegJNI.so.
#
#   tools/build-ffmpeg.sh [path to the Android NDK]
#
# The NDK defaults to $ANDROID_NDK_HOME, else the version ffmpeg-decoder asks for in the SDK.
set -euo pipefail

FFMPEG_BRANCH="release/6.0"
# What Nitrate decodes in software when the device can't: Dolby Digital (Plus), DTS (core of
# DTS-HD too), Dolby TrueHD (and MLP, its base), ALAC.
DECODERS=(ac3 eac3 dca mlp truehd alac)
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/ffmpeg-decoder/src/main/jni"
NDK_VERSION="$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' "$ROOT/ffmpeg-decoder/build.gradle.kts")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null)}}"
NDK="${1:-${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}}"
HOST="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
[[ -d "$TOOLS" ]] || { echo "No NDK toolchain at $TOOLS (install ndk;$NDK_VERSION)"; exit 1; }

if [[ ! -d "$JNI/ffmpeg/.git" ]]; then
    rm -rf "$JNI/ffmpeg"
    git clone --depth 1 --branch "$FFMPEG_BRANCH" https://git.ffmpeg.org/ffmpeg.git "$JNI/ffmpeg" \
        || git clone --depth 1 --branch "$FFMPEG_BRANCH" https://github.com/FFmpeg/FFmpeg.git "$JNI/ffmpeg"
fi
cd "$JNI/ffmpeg"

OPTIONS=(
    --target-os=android --enable-static --disable-shared --disable-doc --disable-programs
    --disable-everything --disable-avdevice --disable-avformat --disable-swscale --disable-postproc
    --disable-avfilter --disable-symver --enable-swresample --disable-v4l2-m2m --disable-vulkan
    --extra-ldexeflags=-pie
)
for d in "${DECODERS[@]}"; do OPTIONS+=("--enable-decoder=$d"); done

build() {
    local abi="$1" arch="$2" cpu="$3" prefix="$4"; shift 4
    ./configure --libdir="android-libs/$abi" --arch="$arch" --cpu="$cpu" \
        --cross-prefix="$TOOLS/$prefix" --nm="$TOOLS/llvm-nm" --ar="$TOOLS/llvm-ar" \
        --ranlib="$TOOLS/llvm-ranlib" --strip="$TOOLS/llvm-strip" "${OPTIONS[@]}" "$@"
    # The licence this build ends up under, for the record (must say LGPL).
    grep -q 'FFMPEG_LICENSE "LGPL' config.h || { echo "FFmpeg configured outside the LGPL"; exit 1; }
    make -j"$(nproc 2>/dev/null || echo 4)"
    make install-libs
    make clean
}
build armeabi-v7a arm armv7-a "armv7a-linux-androideabi$API-" --extra-cflags="-march=armv7-a -mfloat-abi=softfp" --extra-ldflags="-Wl,--fix-cortex-a8"
build arm64-v8a aarch64 armv8-a "aarch64-linux-android$API-"
echo "FFmpeg ($FFMPEG_BRANCH, LGPL, ${DECODERS[*]}) built into $JNI/ffmpeg/android-libs"

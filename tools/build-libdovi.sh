#!/usr/bin/env bash
# Builds libdovi (quietvoid/dovi_tool's dolby_vision crate, MIT) as a static library for
# armeabi-v7a and arm64-v8a, where dovi-converter/src/main/jni/CMakeLists.txt links it into
# libdoviJNI.so. Nitrate uses it to rewrite dual-layer Dolby Vision (profile 7) metadata as
# profile 8.1 while playing, so devices that decode profile 8 show it as Dolby Vision.
#
#   tools/build-libdovi.sh [path to the Android NDK]
#
# Needs Rust (rustup) with the aarch64-linux-android and armv7-linux-androideabi targets; the
# script adds them. The NDK defaults to $ANDROID_NDK_HOME, else the one dovi-converter asks for.
set -euo pipefail

LIBDOVI_TAG="libdovi-3.4.0"
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/dovi-converter/src/main/jni"
WORK="$JNI/libdovi"
NDK_VERSION="$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' "$ROOT/dovi-converter/build.gradle.kts")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null)}}"
NDK="${1:-${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}}"
HOST="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
[[ -d "$TOOLS" ]] || { echo "No NDK toolchain at $TOOLS (install ndk;$NDK_VERSION)"; exit 1; }
command -v cargo >/dev/null || { echo "Rust is needed (https://rustup.rs)"; exit 1; }
rustup target add aarch64-linux-android armv7-linux-androideabi >/dev/null

if [[ ! -d "$WORK/src/.git" ]]; then
    rm -rf "$WORK/src"
    git clone --depth 1 --branch "$LIBDOVI_TAG" https://github.com/quietvoid/dovi_tool.git "$WORK/src"
fi

build() {
    local abi="$1" target="$2" clang="$3"
    local env_target
    env_target="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
    export "CARGO_TARGET_${env_target}_LINKER=$TOOLS/$clang"
    export "CC_${target//-/_}=$TOOLS/$clang"
    export "AR_${target//-/_}=$TOOLS/llvm-ar"
    # The C API (capi) as a static library; a panic aborts instead of unwinding into C.
    (cd "$WORK/src/dolby_vision" && RUSTFLAGS="-Cpanic=abort -Cembed-bitcode=yes" cargo rustc --locked --release --lib \
        --features capi --target "$target" --crate-type staticlib)
    mkdir -p "$WORK/android-libs/$abi"
    cp "$WORK/src/dolby_vision/target/$target/release/libdolby_vision.a" "$WORK/android-libs/$abi/libdovi.a"
    echo "built $abi"
}
build arm64-v8a aarch64-linux-android "aarch64-linux-android$API-clang"
build armeabi-v7a armv7-linux-androideabi "armv7a-linux-androideabi$API-clang"
echo "libdovi ($LIBDOVI_TAG) built into $WORK/android-libs"

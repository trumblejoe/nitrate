#!/usr/bin/env bash
# Builds libass and what it needs (FreeType, FriBidi, HarfBuzz) as static libraries for
# armeabi-v7a and arm64-v8a, where ass-renderer/src/main/jni/CMakeLists.txt links them into
# libassJNI.so. libass is ISC, FreeType is used under its FreeType License (FTL), HarfBuzz is
# "Old MIT", FriBidi is LGPL 2.1+ (linked into a separate shared library). No fontconfig: fonts
# come from the file's attachments and Android's own fonts.
#
#   tools/build-libass.sh [path to the Android NDK]
#
# Needs meson and ninja. The NDK defaults to $ANDROID_NDK_HOME, else the one ass-renderer asks for.
set -euo pipefail

FREETYPE=2.13.3
FRIBIDI=1.0.16
HARFBUZZ=10.1.0
LIBASS=0.17.3
API=24

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/ass-renderer/src/main/jni"
WORK="$JNI/libass"
NDK_VERSION="$(sed -n 's/.*ndkVersion = "\(.*\)".*/\1/p' "$ROOT/ass-renderer/build.gradle.kts")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null)}}"
NDK="${1:-${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VERSION}}"
HOST="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
TOOLS="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
[[ -d "$TOOLS" ]] || { echo "No NDK toolchain at $TOOLS (install ndk;$NDK_VERSION)"; exit 1; }
command -v meson >/dev/null || { echo "meson is needed (pip install meson)"; exit 1; }

mkdir -p "$WORK/src"
fetch() {
    local name="$1" url="$2"
    [[ -d "$WORK/src/$name" ]] && return
    mkdir -p "$WORK/src/$name"
    curl -fsSL "$url" | tar -x --strip-components=1 -C "$WORK/src/$name" -f - --use-compress-program="$( [[ "$url" == *.xz ]] && echo xz || echo gzip )"
}
fetch freetype "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE.tar.xz"
fetch fribidi "https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI/fribidi-$FRIBIDI.tar.xz"
fetch harfbuzz "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ/harfbuzz-$HARFBUZZ.tar.xz"
fetch libass "https://github.com/libass/libass/releases/download/$LIBASS/libass-$LIBASS.tar.xz"

build() {
    local abi="$1" triple="$2" cpu_family="$3" cpu="$4"
    local prefix="$WORK/android-libs/$abi"
    local cross="$WORK/cross-$abi.txt"
    cat > "$cross" <<CROSS
[binaries]
c = '$TOOLS/$triple$API-clang'
cpp = '$TOOLS/$triple$API-clang++'
ar = '$TOOLS/llvm-ar'
strip = '$TOOLS/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIC', '-O2']
cpp_args = ['-fPIC', '-O2']
default_library = 'static'
prefix = '$prefix'
libdir = 'lib'
pkg_config_path = '$prefix/lib/pkgconfig'

[properties]
pkg_config_libdir = '$prefix/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$cpu'
endian = 'little'
CROSS
    one() {
        local name="$1"; shift
        local dir="$WORK/build/$abi/$name"
        rm -rf "$dir"
        meson setup "$dir" "$WORK/src/$name" --cross-file "$cross" --buildtype=release "$@" >/dev/null
        ninja -C "$dir" install >/dev/null
    }
    one freetype -Dzlib=disabled -Dbzip2=disabled -Dpng=disabled -Dharfbuzz=disabled -Dbrotli=disabled
    one fribidi -Ddocs=false -Dtests=false -Dbin=false
    one harfbuzz -Dfreetype=enabled -Dglib=disabled -Dgobject=disabled -Dcairo=disabled -Dicu=disabled \
        -Dtests=disabled -Ddocs=disabled -Dutilities=disabled -Dintrospection=disabled -Dchafa=disabled
    one libass -Dfontconfig=disabled -Ddirectwrite=disabled -Dcoretext=disabled -Drequire-system-font-provider=false \
        -Dasm=disabled -Dlibunibreak=disabled -Dtest=false -Dprofile=false
    echo "built $abi"
}
build arm64-v8a aarch64-linux-android aarch64 armv8-a
build armeabi-v7a armv7a-linux-androideabi arm armv7-a
echo "libass $LIBASS (FreeType $FREETYPE, FriBidi $FRIBIDI, HarfBuzz $HARFBUZZ) built into $WORK/android-libs"

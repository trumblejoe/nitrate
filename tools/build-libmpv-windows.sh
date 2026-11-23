#!/usr/bin/env bash
# Builds libmpv for the Windows desktop app (x64) as an LGPL library, never GPL: FFmpeg configured
# without --enable-gpl/--enable-nonfree (and without libpostproc, which is GPL), mpv with
# -Dgpl=false and every GPL-only or unneeded part switched off. The other libraries come from
# MSYS2's packages (libass, FreeType, HarfBuzz, FriBidi, libplacebo, dav1d, Little CMS, shaderc,
# SPIRV-Cross, the Vulkan loader…). Every DLL that ends up beside libmpv-2.dll must be on the
# licence list below (ALLOWED); anything else stops the build, so nothing unchecked ships. Each
# DLL's package licence files are copied into <out>/licenses, with SOURCES.txt saying where every
# source is (the LGPL asks that people can rebuild and replace these libraries).
#
#   tools/build-libmpv-windows.sh [out dir]      (default: desktop/resources/windows-x64)
#
# Runs in an MSYS2 UCRT64 shell on Windows (GitHub's windows-latest with msys2/setup-msys2, see
# .github/workflows/release.yml's "windows" job). Takes a while: FFmpeg and mpv are compiled.
set -euo pipefail

FFMPEG=7.1.1
MPV=0.40.0

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/desktop/resources/windows-x64}"
WORK="${NITRATE_MPV_WORK:-$ROOT/build/libmpv-windows}"
PREFIX="$WORK/prefix"

[[ "${MSYSTEM:-}" == "UCRT64" ]] || { echo "Run this in an MSYS2 UCRT64 shell (MSYSTEM=UCRT64)."; exit 1; }
P=mingw-w64-ucrt-x86_64

# Build tools and the libraries mpv uses (all LGPL-compatible: see ALLOWED below).
pacman -S --needed --noconfirm \
    make diffutils tar curl \
    $P-gcc $P-meson $P-ninja $P-pkgconf $P-nasm $P-ntldd \
    $P-libass $P-freetype $P-harfbuzz $P-fribidi $P-libplacebo $P-dav1d $P-lcms2 $P-zlib \
    $P-shaderc $P-spirv-cross $P-vulkan-headers $P-vulkan-loader $P-ffnvcodec-headers $P-libiconv

mkdir -p "$WORK/src" "$PREFIX"
fetch() {
    local name="$1" url="$2"
    [[ -d "$WORK/src/$name" ]] && return
    mkdir -p "$WORK/src/$name"
    curl -fsSL "$url" | tar -x --strip-components=1 -C "$WORK/src/$name" -f - --use-compress-program="$( [[ "$url" == *.xz ]] && echo xz || echo gzip )"
}
fetch ffmpeg "https://ffmpeg.org/releases/ffmpeg-$FFMPEG.tar.xz"
fetch mpv "https://github.com/mpv-player/mpv/archive/refs/tags/v$MPV.tar.gz"

# FFmpeg, LGPL 2.1+: no GPL or non-free parts, no autodetected extras; decoders, demuxers and
# Windows' hardware decoding (D3D11VA, DXVA2, NVDEC), HTTPS through Windows' own Schannel.
if [[ ! -f "$PREFIX/lib/pkgconfig/libavcodec.pc" ]]; then
    (
        cd "$WORK/src/ffmpeg"
        ./configure --prefix="$PREFIX" --target-os=mingw32 --arch=x86_64 \
            --enable-shared --disable-static --disable-programs --disable-doc --disable-debug \
            --disable-autodetect --disable-postproc --disable-avdevice \
            --enable-zlib --enable-iconv --enable-schannel \
            --enable-d3d11va --enable-dxva2 --enable-ffnvcodec --enable-nvdec \
            --enable-libdav1d \
            --extra-libs="-liconv"
        # The configuration must be LGPL: GPL or non-free parts are a mistake here.
        if grep -Eq '^CONFIG_(GPL|NONFREE|GPLV3)=yes' ffbuild/config.mak; then echo "FFmpeg was configured with GPL or non-free parts"; exit 1; fi
        make -j"$(nproc)"
        make install
    )
fi

# libmpv, LGPL 2.1+ (-Dgpl=false), no player program; D3D11 and Vulkan through libplacebo (gpu-next), WASAPI.
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
if [[ ! -f "$PREFIX/bin/libmpv-2.dll" ]]; then
    (
        cd "$WORK/src/mpv"
        rm -rf build
        meson setup build --prefix="$PREFIX" --buildtype=release \
            -Dlibmpv=true -Dcplayer=false -Dgpl=false -Ddefault_library=shared \
            -Dlua=disabled -Djavascript=disabled -Dlibarchive=disabled -Drubberband=disabled \
            -Dcdda=disabled -Ddvdnav=disabled -Dlibbluray=disabled -Duchardet=disabled \
            -Dvapoursynth=disabled -Dzimg=disabled -Djpeg=disabled -Dsdl2-audio=disabled \
            -Dsdl2-video=disabled -Dopenal=disabled -Dlibavdevice=disabled \
            -Dmanpage-build=disabled -Dhtml-build=disabled -Dpdf-build=disabled \
            -Dd3d11=enabled -Dd3d-hwaccel=enabled -Dwasapi=enabled -Dvulkan=enabled \
            -Dshaderc=enabled -Dspirv-cross=enabled -Dlcms2=enabled -Diconv=enabled
        meson configure build | grep -E '^\s*gpl\s+false' >/dev/null || { echo "mpv isn't configured with gpl=false"; exit 1; }
        meson compile -C build
        meson install -C build
    )
fi

# What libmpv-2.dll needs, outside Windows itself: ours from the prefix, the rest from MSYS2.
rm -rf "$OUT"
mkdir -p "$OUT/licenses"
cp "$PREFIX/bin/libmpv-2.dll" "$OUT/"
dlls() { ntldd -R "$1" 2>/dev/null | awk '{print $3}' | tr '\\' '/' | grep -iE "/(ucrt64|prefix)/bin/" || true; }
for dll in $(dlls "$PREFIX/bin/libmpv-2.dll" | sort -u); do
    unix="$(cygpath -u "$dll")"
    cp "$unix" "$OUT/"
done

# The licence list: every DLL shipped and what it's under. LGPL libraries are separate DLLs
# loaded at run time, replaceable by the user's own builds.
declare -A ALLOWED=(
    ['libmpv-2.dll']='mpv, LGPL 2.1+ (built with -Dgpl=false)'
    ['avcodec-*.dll']='FFmpeg, LGPL 2.1+' ['avformat-*.dll']='FFmpeg, LGPL 2.1+' ['avutil-*.dll']='FFmpeg, LGPL 2.1+'
    ['avfilter-*.dll']='FFmpeg, LGPL 2.1+' ['swscale-*.dll']='FFmpeg, LGPL 2.1+' ['swresample-*.dll']='FFmpeg, LGPL 2.1+'
    ['libass-*.dll']='libass, ISC' ['libfreetype-*.dll']='FreeType, FreeType License' ['libharfbuzz-*.dll']='HarfBuzz, MIT'
    ['libfribidi-*.dll']='GNU FriBidi, LGPL 2.1+' ['libplacebo-*.dll']='libplacebo, LGPL 2.1+' ['libdav1d*.dll']='dav1d, BSD 2-Clause'
    ['liblcms2-*.dll']='Little CMS, MIT' ['zlib1.dll']='zlib, zlib License' ['libz-*.dll']='zlib, zlib License' ['libbz2-*.dll']='bzip2, BSD-style'
    ['libpng16-*.dll']='libpng, libpng License' ['libbrotli*.dll']='Brotli, MIT' ['libglib-2.0-*.dll']='GLib, LGPL 2.1+'
    ['libintl-*.dll']='gettext runtime, LGPL 2.1+' ['libiconv-*.dll']='libiconv, LGPL 2.1+' ['libpcre2-8-*.dll']='PCRE2, BSD 3-Clause'
    ['libgraphite2.dll']='Graphite2, LGPL 2.1+ (of its licence choices)' ['libshaderc_shared.dll']='shaderc, Apache 2.0'
    ['libspirv-cross-c-shared.dll']='SPIRV-Cross, Apache 2.0' ['vulkan-1.dll']='Vulkan loader, Apache 2.0'
    ['libunibreak-*.dll']='libunibreak, zlib License' ['libxxhash.dll']='xxHash, BSD 2-Clause' ['libdovi.dll']='libdovi, MIT'
    ['libexpat-*.dll']='Expat, MIT' ['libfontconfig-*.dll']='Fontconfig, MIT-style'
    ['libstdc++-6.dll']='GCC runtime, GPL 3 with the GCC Runtime Library Exception' ['libgcc_s_seh-1.dll']='GCC runtime, GPL 3 with the GCC Runtime Library Exception'
    ['libwinpthread-1.dll']='mingw-w64 winpthreads, MIT and BSD'
)
# Never these (GPL): if one ever appears, the build stops even before the list is checked.
FORBIDDEN='x264|x265|xvid|postproc|rubberband|libcdio|dvdnav|dvdread|libmp3lame-gpl|frei0r|vidstab'

: > "$OUT/licenses/DLLs.txt"
for f in "$OUT"/*.dll; do
    name="$(basename "$f")"
    if echo "$name" | grep -Eiq "$FORBIDDEN"; then echo "GPL library pulled in: $name"; exit 1; fi
    licence=""
    for pattern in "${!ALLOWED[@]}"; do
        # shellcheck disable=SC2053
        if [[ "${name,,}" == ${pattern,,} ]]; then licence="${ALLOWED[$pattern]}"; break; fi
    done
    if [[ -z "$licence" ]]; then echo "Not on the licence list (check it, then add it to ALLOWED): $name"; exit 1; fi
    echo "$name: $licence" >> "$OUT/licenses/DLLs.txt"
    # The owning package's own licence files, from MSYS2 (ours: FFmpeg's and mpv's, below).
    pkg="$(pacman -Qqo "/ucrt64/bin/$name" 2>/dev/null || true)"
    if [[ -n "$pkg" && -d "/ucrt64/share/licenses/${pkg#mingw-w64-ucrt-x86_64-}" ]]; then
        cp -r "/ucrt64/share/licenses/${pkg#mingw-w64-ucrt-x86_64-}" "$OUT/licenses/" 2>/dev/null || true
    fi
done
mkdir -p "$OUT/licenses/ffmpeg" "$OUT/licenses/mpv"
cp "$WORK/src/ffmpeg/COPYING.LGPLv2.1" "$WORK/src/ffmpeg/LICENSE.md" "$OUT/licenses/ffmpeg/"
cp "$WORK/src/mpv/LICENSE.LGPL" "$WORK/src/mpv/Copyright" "$OUT/licenses/mpv/"
cat > "$OUT/licenses/SOURCES.txt" <<SOURCES
Nitrate for Windows ships libmpv and the libraries listed in DLLs.txt as separate DLLs.
They can be rebuilt and replaced: the build is tools/build-libmpv-windows.sh in
https://github.com/trumblejoe/nitrate (the release's tag), from these sources:
  FFmpeg $FFMPEG (LGPL 2.1+, configured without GPL or non-free parts): https://ffmpeg.org/releases/ffmpeg-$FFMPEG.tar.xz
  mpv $MPV (LGPL 2.1+, -Dgpl=false): https://github.com/mpv-player/mpv/archive/refs/tags/v$MPV.tar.gz
  The other DLLs: MSYS2's mingw-w64-ucrt-x86_64 packages, sources at https://github.com/msys2/MINGW-packages
  and each project's own site.
SOURCES
pacman -Q | grep "^$P-" | sed "s/^$P-//" > "$OUT/licenses/msys2-packages.txt"

echo "libmpv for Windows in $OUT:"
cat "$OUT/licenses/DLLs.txt"

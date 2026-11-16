# libass subtitle renderer

Draws styled (ASS/SSA) subtitles the way their authors made them: fonts (including the ones the
file carries), colours, outlines, positions, rotation, karaoke and signs. libass renders each
moment into a bitmap that `core-android`'s `AssSubtitleView` shows in a layer over the picture,
inside `PlayerView`'s content frame, so the video frames themselves are never touched (HDR, Dolby
Vision and tunneled playback stay as they are).

- `src/main/jni/ass_jni.c`: a small JNI bridge (Nitrate's own code): load a track and its fonts,
  set the size, render a moment into an Android bitmap only when it changed.
- libass itself is built by `tools/build-libass.sh` as static libraries for `armeabi-v7a` and
  `arm64-v8a` with what it needs, all linked into `libassJNI.so`, which the app loads at run time:
  - [libass](https://github.com/libass/libass) 0.17.3, ISC License
  - [FreeType](https://freetype.org) 2.13.3, FreeType License (FTL)
  - [HarfBuzz](https://harfbuzz.github.io) 10.1.0, MIT ("Old MIT")
  - [GNU FriBidi](https://github.com/fribidi/fribidi) 1.0.16, LGPL 2.1 or later
  No fontconfig: fonts come from the file's attachments (Jellyfin's `Attachments` endpoint, or
  saved with a download) and Android's own Roboto for anything missing. The versions and options
  are in the script; see `NOTICE`.
- Without that build the module compiles with no native code and reports itself unavailable;
  styled subtitles then use Media3's simpler drawing (colours and positions, not the file's
  fonts).

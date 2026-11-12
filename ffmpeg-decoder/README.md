# FFmpeg audio decoder

Software decoding for audio the device can't decode itself and the TV or receiver can't take over
HDMI: DTS (and the DTS core of DTS-HD), Dolby TrueHD, Dolby Digital (Plus) and ALAC.

- `src/main/java`, `src/main/jni`: Media3's FFmpeg extension at the Media3 version Nitrate uses
  (`androidx/media`, `libraries/decoder_ffmpeg`), Apache License 2.0, unchanged except that the
  experimental video renderer is left out. See `NOTICE`.
- FFmpeg itself is built by `tools/build-ffmpeg.sh` under the **LGPL 2.1 or later** (no GPL or
  non-free parts), audio decoders only, for `armeabi-v7a` and `arm64-v8a`, and linked into
  `libffmpegJNI.so`, which the app loads at run time. The FFmpeg version and the exact configure
  options are in that script; FFmpeg's source is at https://ffmpeg.org/download.html.
- Without the FFmpeg build the module compiles without native code and reports itself
  unavailable: playback then leaves those formats to the server, as before.

Updating Media3: copy the new extension sources over these (keeping the video renderer out) and
re-run `tools/build-ffmpeg.sh`.

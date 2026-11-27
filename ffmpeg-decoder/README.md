# FFmpeg decoders

Software decoding for audio the device can't decode itself and the TV or receiver can't take over
HDMI: DTS (and the DTS core of DTS-HD), Dolby TrueHD, Dolby Digital (Plus) and ALAC; Opus with more
than two channels, ahead of the device's own decoder (`MultichannelOpusRenderer`: some devices' put
the centre channel in the left front); and for video
the device has no decoder for, up to 1080p: H.264 (including 10-bit "Hi10P", for devices whose
hardware is 8-bit only), MPEG-2, MPEG-4 Part 2 and VC-1 (from MKV).

- `src/main/java/dev/nitrate/ffmpeg`, `src/main/jni/ffmpeg_video_jni.cc`: Nitrate's own video
  decoder and renderer (Media3's FFmpeg video renderer is only a stub upstream). Frames come out
  as 8-bit 4:2:0 (10-bit and other layouts converted with libswscale) and are copied into the
  Surface as YV12. It sits after the device's own renderer, so hardware always goes first. There's
  no deinterlacing, so the server profile leaves interlaced video to the server.

- `src/main/java`, `src/main/jni`: Media3's FFmpeg extension at the Media3 version Nitrate uses
  (`androidx/media`, `libraries/decoder_ffmpeg`), Apache License 2.0, unchanged except that the
  experimental video renderer is left out. See `NOTICE`.
- FFmpeg itself is built by `tools/build-ffmpeg.sh` under the **LGPL 2.1 or later** (no GPL or
  non-free parts), only the decoders above (plus libswscale and libswresample), for `armeabi-v7a` and `arm64-v8a`, and linked into
  `libffmpegJNI.so`, which the app loads at run time. The FFmpeg version and the exact configure
  options are in that script; FFmpeg's source is at https://ffmpeg.org/download.html.
- Without the FFmpeg build the module compiles without native code and reports itself
  unavailable: playback then leaves those formats to the server, as before.

Updating Media3: copy the new extension sources over these (keeping the video renderer out) and
re-run `tools/build-ffmpeg.sh`.

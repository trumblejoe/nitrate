# Dolby Vision profile 7 → 8.1

UHD Blu-ray Dolby Vision is dual-layer profile 7, which almost no Android device decodes. Nearly
every Dolby Vision device decodes profile 8.1, whose base layer is the same HDR10 picture. While a
profile 7 file plays, core-android's `DolbyVisionProfile7` drops the enhancement layer from each
frame and has this module rewrite the frame's Dolby Vision metadata (its RPU) as profile 8.1, so
the device's own decoder shows it as Dolby Vision.

- `src/main/jni/dovi_jni.c`: a small JNI bridge (Nitrate's own code): one RPU NAL unit in,
  converted with libdovi's mode 2 (profile 8.1; a full enhancement layer's mapping becomes a
  no-op, as it should once that layer is gone), one out.
- [libdovi](https://github.com/quietvoid/dovi_tool) 3.4.0 (`dolby_vision` crate, MIT) is built
  by `tools/build-libdovi.sh` as a static library for `armeabi-v7a` and `arm64-v8a` (needs Rust
  via rustup and the Android NDK) and linked into `libdoviJNI.so`. The Rust crates it brings are
  all used under MIT; see `core-android/src/main/assets/licenses/MIT-Rust.txt`.
- Without that build the module compiles with no native code and reports itself unavailable;
  profile 7 then plays as its HDR10 base layer.
- Nothing about the base layer changes: a minimal enhancement layer (most discs) loses nothing,
  a full one loses its extra detail.

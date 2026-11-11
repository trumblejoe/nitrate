#!/usr/bin/env bash
# Regenerates the short clips used by the in-app playback test (app/src/main/assets/testclips).
# Each clip is ~2 seconds and encoded at high compression: they test decoding, not picture quality.
# Requires ffmpeg with libx264, libx265, libvpx, libsvtav1, libopus, libmp3lame, libvorbis.
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/testclips"
mkdir -p "$OUT"
cd "$OUT"
FF=(ffmpeg -hide_banner -loglevel error -y)
D=2

src() { echo "testsrc2=size=$1:rate=24:duration=$D"; }
tone() { echo "sine=frequency=440:duration=$D"; }

# Video only, so a failure points at the video decoder.
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 40 -an h264_1080p.mp4
"${FF[@]}" -f lavfi -i "$(src 3840x2160)" -c:v libx264 -profile:v high -level 5.1 -pix_fmt yuv420p -crf 42 -an h264_2160p.mp4
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v libx264 -profile:v high10 -pix_fmt yuv420p10le -crf 40 -an h264_1080p_10bit.mp4
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v libx265 -pix_fmt yuv420p -crf 40 -tag:v hvc1 -x265-params log-level=error -an hevc_1080p.mp4
"${FF[@]}" -f lavfi -i "$(src 3840x2160)" -c:v libx265 -pix_fmt yuv420p10le -crf 42 -tag:v hvc1 -x265-params log-level=error -an hevc_2160p_10bit.mp4
"${FF[@]}" -f lavfi -i "$(src 3840x2160)" -c:v libx265 -pix_fmt yuv420p10le -crf 42 -tag:v hvc1 \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc \
  -x265-params "log-level=error:hdr10=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
  -an hevc_2160p_hdr10.mp4
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v libx265 -pix_fmt yuv420p10le -crf 40 -tag:v hvc1 \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc \
  -x265-params "log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" -an hevc_1080p_hlg.mp4
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v libvpx-vp9 -profile:v 0 -pix_fmt yuv420p -crf 50 -b:v 0 -deadline realtime -cpu-used 8 -an vp9_1080p.webm
"${FF[@]}" -f lavfi -i "$(src 3840x2160)" -c:v libvpx-vp9 -profile:v 2 -pix_fmt yuv420p10le -crf 55 -b:v 0 -deadline realtime -cpu-used 8 -an vp9_2160p_10bit.webm
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v libsvtav1 -pix_fmt yuv420p -crf 55 -preset 12 -svtav1-params verbosity=0 -an av1_1080p.mp4
"${FF[@]}" -f lavfi -i "$(src 3840x2160)" -c:v libsvtav1 -pix_fmt yuv420p10le -crf 58 -preset 12 -svtav1-params verbosity=0 -an av1_2160p_10bit.mp4
"${FF[@]}" -f lavfi -i "$(src 1920x1080)" -c:v mpeg2video -q:v 20 -an mpeg2_1080p.ts

# Audio only, so a failure points at the audio decoder or HDMI passthrough.
"${FF[@]}" -f lavfi -i "$(tone)" -ac 2 -c:a aac -b:a 96k aac_stereo.m4a
"${FF[@]}" -f lavfi -i "$(tone)" -ac 2 -c:a libmp3lame -b:a 96k mp3_stereo.mp3
"${FF[@]}" -f lavfi -i "$(tone)" -ac 6 -c:a ac3 -b:a 384k ac3_51.mka
"${FF[@]}" -f lavfi -i "$(tone)" -ac 6 -c:a eac3 -b:a 384k eac3_51.mka
"${FF[@]}" -f lavfi -i "$(tone)" -ac 6 -c:a dca -strict -2 -b:a 768k dts_51.mka
"${FF[@]}" -f lavfi -i "$(tone)" -ac 6 -ar 48000 -c:a truehd -strict -2 truehd_51.mka
"${FF[@]}" -f lavfi -i "$(tone)" -ac 2 -c:a libopus -b:a 64k opus_stereo.webm
"${FF[@]}" -f lavfi -i "$(tone)" -ac 2 -c:a libvorbis -q:a 2 vorbis_stereo.ogg
"${FF[@]}" -f lavfi -i "$(tone)" -ac 2 -c:a flac flac_stereo.flac

ls -la "$OUT"
du -sh "$OUT"

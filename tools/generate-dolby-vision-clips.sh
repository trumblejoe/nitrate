#!/usr/bin/env bash
# Generates the Dolby Vision clips for the in-app playback test (app/src/main/assets/testclips):
#   dv_p81_2160p.mp4  Profile 8.1: HDR10 base layer + RPU. Plays as HDR10 without Dolby Vision.
#   dv_p5_2160p.mp4   Profile 5: no compatible base layer. Without Dolby Vision processing the
#                     colours look visibly wrong (green/purple), which makes support easy to judge.
# The RPU is generated, not mastered: it describes a 1000-nit HDR10-like grade with no trims.
#
# Requires ffmpeg (libx265), dovi_tool (https://github.com/quietvoid/dovi_tool) and Dolby's
# mp4muxer (https://github.com/DolbyLaboratories/dlb_mp4base), on PATH or given as
# DOVI_TOOL=... MP4MUXER=... . ffmpeg 6 can't write the Dolby Vision MP4 header itself.
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/testclips"
DOVI_TOOL="${DOVI_TOOL:-$(command -v dovi_tool)}"
MP4MUXER="${MP4MUXER:-$(command -v mp4muxer)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FF=(ffmpeg -hide_banner -loglevel error -y)
FRAMES=48   # 2 s at 24 fps
SRC="testsrc2=size=3840x2160:rate=24:duration=2"

# Base layers. 8.1 is a normal HDR10 encode; 5 is the same picture flagged with unspecified
# colour (as Profile 5 streams are), since its true colour space only Dolby Vision can interpret.
"${FF[@]}" -f lavfi -i "$SRC" -c:v libx265 -pix_fmt yuv420p10le -crf 42 \
  -x265-params "log-level=error:hdr10=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1):max-cll=1000,400" \
  -f hevc "$WORK/base81.hevc"
"${FF[@]}" -f lavfi -i "$SRC" -c:v libx265 -pix_fmt yuv420p10le -crf 42 \
  -x265-params "log-level=error:repeat-headers=1:colorprim=2:transfer=2:colormatrix=2" \
  -f hevc "$WORK/base5.hevc"

for profile in 8.1 5; do
  cat > "$WORK/rpu-$profile.json" <<JSON
{
  "cm_version": "V40",
  "profile": "$profile",
  "length": $FRAMES,
  "level6": {
    "max_display_mastering_luminance": 1000,
    "min_display_mastering_luminance": 1,
    "max_content_light_level": 1000,
    "max_frame_average_light_level": 400
  }
}
JSON
  "$DOVI_TOOL" generate -j "$WORK/rpu-$profile.json" -o "$WORK/rpu-$profile.bin"
done

"$DOVI_TOOL" inject-rpu -i "$WORK/base81.hevc" --rpu-in "$WORK/rpu-8.1.bin" -o "$WORK/dv81.hevc"
"$DOVI_TOOL" inject-rpu -i "$WORK/base5.hevc" --rpu-in "$WORK/rpu-5.bin" -o "$WORK/dv5.hevc"

"$MP4MUXER" -i "$WORK/dv81.hevc" -o "$OUT/dv_p81_2160p.mp4" --dv-profile 8 --dv-bl-compatible-id 1 \
  --mpeg4-comp-brand mp42,iso6,isom,msdh,dby1 --overwrite >/dev/null
"$MP4MUXER" -i "$WORK/dv5.hevc" -o "$OUT/dv_p5_2160p.mp4" --dv-profile 5 \
  --mpeg4-comp-brand mp42,iso6,isom,msdh,dby1 --overwrite >/dev/null

# Verify: the container must carry the right Dolby Vision configuration, and every frame an RPU.
for f in dv_p81_2160p.mp4:8:1 dv_p5_2160p.mp4:5:0; do
  IFS=: read -r file profile compat <<<"$f"
  conf=$(ffprobe -v error -select_streams v:0 -show_entries stream_side_data=dv_profile,dv_bl_signal_compatibility_id,rpu_present_flag,bl_present_flag -of csv=p=0 "$OUT/$file")
  rpus=$(ffprobe -v error -select_streams v:0 -show_frames -show_entries frame_side_data=side_data_type -of csv=p=0 "$OUT/$file" | grep -c "Dolby Vision" || true)
  echo "$file: config [$conf], frames with Dolby Vision metadata: $rpus/$FRAMES"
  [[ $conf == *"$profile"* && $rpus -eq $FRAMES ]] || { echo "  FAILED: expected profile $profile with an RPU on every frame" >&2; exit 1; }
done
ls -la "$OUT"/dv_*
